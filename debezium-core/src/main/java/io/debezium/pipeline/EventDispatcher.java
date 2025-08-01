/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.pipeline;

import static io.debezium.config.CommonConnectorConfig.WatermarkStrategy.INSERT_DELETE;
import static io.debezium.util.Loggings.maybeRedactSensitiveData;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.StreamSupport;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.header.ConnectHeaders;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.connector.SnapshotRecord;
import io.debezium.connector.base.ChangeEventQueue;
import io.debezium.connector.common.DebeziumHeaderProducer;
import io.debezium.data.Envelope;
import io.debezium.data.Envelope.Operation;
import io.debezium.heartbeat.Heartbeat;
import io.debezium.heartbeat.Heartbeat.ScheduledHeartbeat;
import io.debezium.heartbeat.HeartbeatFactory;
import io.debezium.pipeline.signal.SignalProcessor;
import io.debezium.pipeline.signal.channels.SourceSignalChannel;
import io.debezium.pipeline.source.snapshot.incremental.IncrementalSnapshotChangeEventSource;
import io.debezium.pipeline.source.spi.DataChangeEventListener;
import io.debezium.pipeline.source.spi.EventMetadataProvider;
import io.debezium.pipeline.spi.ChangeEventCreator;
import io.debezium.pipeline.spi.ChangeRecordEmitter;
import io.debezium.pipeline.spi.ChangeRecordEmitter.Receiver;
import io.debezium.pipeline.spi.OffsetContext;
import io.debezium.pipeline.spi.Partition;
import io.debezium.pipeline.spi.SchemaChangeEventEmitter;
import io.debezium.pipeline.txmetadata.DefaultTransactionInfo;
import io.debezium.pipeline.txmetadata.TransactionInfo;
import io.debezium.pipeline.txmetadata.TransactionMonitor;
import io.debezium.processors.PostProcessorRegistry;
import io.debezium.processors.spi.PostProcessor;
import io.debezium.relational.history.ConnectTableChangeSerializer;
import io.debezium.relational.history.HistoryRecord.Fields;
import io.debezium.schema.DataCollectionFilters.DataCollectionFilter;
import io.debezium.schema.DataCollectionSchema;
import io.debezium.schema.DatabaseSchema;
import io.debezium.schema.HistorizedDatabaseSchema;
import io.debezium.schema.SchemaChangeEvent;
import io.debezium.schema.SchemaFactory;
import io.debezium.schema.SchemaNameAdjuster;
import io.debezium.spi.schema.DataCollectionId;
import io.debezium.spi.topic.TopicNamingStrategy;

/**
 * Central dispatcher for data change and schema change events. The former will be routed to the change event queue, the
 * latter will be routed to the {@link DatabaseSchema}. But based on the applying include/exclude list configuration,
 * events may be not be dispatched at all.
 * <p>
 * This router is also in charge of emitting heartbeat messages, exposing of metrics via JMX etc.
 *
 * @author Gunnar Morling
 */
public class EventDispatcher<P extends Partition, T extends DataCollectionId> implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventDispatcher.class);

    protected final TransactionMonitor transactionMonitor;
    private final TopicNamingStrategy<T> topicNamingStrategy;
    private final DatabaseSchema<T> schema;
    private final HistorizedDatabaseSchema<T> historizedSchema;
    private final ChangeEventQueue<DataChangeEvent> queue;
    private final DataCollectionFilter<T> filter;
    private final ChangeEventCreator changeEventCreator;
    private final DebeziumHeaderProducer debeziumHeaderProducer;
    private final Heartbeat heartbeat;
    private DataChangeEventListener<P> eventListener = DataChangeEventListener.NO_OP();
    private final boolean emitTombstonesOnDelete;
    private final InconsistentSchemaHandler<P, T> inconsistentSchemaHandler;
    private final CommonConnectorConfig connectorConfig;
    private final EnumSet<Operation> skippedOperations;
    private final boolean neverSkip;

    private final Schema schemaChangeKeySchema;
    private final Schema schemaChangeValueSchema;
    private final ConnectTableChangeSerializer tableChangesSerializer;
    private final SourceSignalChannel sourceSignalChannel;
    private IncrementalSnapshotChangeEventSource<P, T> incrementalSnapshotChangeEventSource;

    /**
     * Change event receiver for events dispatched from a streaming change event source.
     */
    private final StreamingChangeRecordReceiver streamingReceiver;

    private final SignalProcessor<P, ?> signalProcessor;

    private final PostProcessorRegistry postProcessorRegistry;

    public EventDispatcher(CommonConnectorConfig connectorConfig, TopicNamingStrategy<T> topicNamingStrategy,
                           DatabaseSchema<T> schema, ChangeEventQueue<DataChangeEvent> queue, DataCollectionFilter<T> filter,
                           ChangeEventCreator changeEventCreator, EventMetadataProvider metadataProvider, SchemaNameAdjuster schemaNameAdjuster,
                           SignalProcessor<P, ?> signalProcessor, DebeziumHeaderProducer debeziumHeaderProducer) {
        this(connectorConfig, topicNamingStrategy, schema, queue, filter, changeEventCreator, null, metadataProvider,
                new HeartbeatFactory<>().getScheduledHeartbeat(connectorConfig, null, null, queue), schemaNameAdjuster,
                signalProcessor,
                debeziumHeaderProducer);
    }

    public EventDispatcher(CommonConnectorConfig connectorConfig, TopicNamingStrategy<T> topicNamingStrategy,
                           DatabaseSchema<T> schema, ChangeEventQueue<DataChangeEvent> queue, DataCollectionFilter<T> filter,
                           ChangeEventCreator changeEventCreator, EventMetadataProvider metadataProvider,
                           Heartbeat heartbeat, SchemaNameAdjuster schemaNameAdjuster, SignalProcessor<P, ?> signalProcessor,
                           DebeziumHeaderProducer debeziumHeaderProducer) {
        this(connectorConfig, topicNamingStrategy, schema, queue, filter, changeEventCreator, null, metadataProvider, heartbeat, schemaNameAdjuster, signalProcessor,
                debeziumHeaderProducer);
    }

    public EventDispatcher(CommonConnectorConfig connectorConfig, TopicNamingStrategy<T> topicNamingStrategy,
                           DatabaseSchema<T> schema, ChangeEventQueue<DataChangeEvent> queue, DataCollectionFilter<T> filter,
                           ChangeEventCreator changeEventCreator, EventMetadataProvider metadataProvider,
                           Heartbeat heartbeat, SchemaNameAdjuster schemaNameAdjuster, DebeziumHeaderProducer debeziumHeaderProducer) {
        this(connectorConfig, topicNamingStrategy, schema, queue, filter, changeEventCreator, null, metadataProvider, heartbeat, schemaNameAdjuster, null,
                debeziumHeaderProducer);
    }

    public EventDispatcher(CommonConnectorConfig connectorConfig, TopicNamingStrategy<T> topicNamingStrategy,
                           DatabaseSchema<T> schema, ChangeEventQueue<DataChangeEvent> queue, DataCollectionFilter<T> filter,
                           ChangeEventCreator changeEventCreator, EventMetadataProvider metadataProvider, SchemaNameAdjuster schemaNameAdjuster,
                           DebeziumHeaderProducer debeziumHeaderProducer) {

        this(connectorConfig, topicNamingStrategy, schema, queue, filter, changeEventCreator, null, metadataProvider,
                new HeartbeatFactory<>().getScheduledHeartbeat(
                        connectorConfig,
                        null,
                        null, queue),
                schemaNameAdjuster, null, debeziumHeaderProducer);
    }

    public EventDispatcher(CommonConnectorConfig connectorConfig, TopicNamingStrategy<T> topicNamingStrategy,
                           DatabaseSchema<T> schema, ChangeEventQueue<DataChangeEvent> queue, DataCollectionFilter<T> filter,
                           ChangeEventCreator changeEventCreator, InconsistentSchemaHandler<P, T> inconsistentSchemaHandler,
                           EventMetadataProvider metadataProvider, Heartbeat heartbeat, SchemaNameAdjuster schemaNameAdjuster,
                           SignalProcessor<P, ?> signalProcessor,
                           DebeziumHeaderProducer debeziumHeaderProducer) {
        this.debeziumHeaderProducer = debeziumHeaderProducer;
        this.tableChangesSerializer = new ConnectTableChangeSerializer(schemaNameAdjuster);
        this.connectorConfig = connectorConfig;
        this.topicNamingStrategy = topicNamingStrategy;
        this.schema = schema;
        this.historizedSchema = schema.isHistorized() ? (HistorizedDatabaseSchema<T>) schema : null;
        this.queue = queue;
        this.filter = filter;
        this.changeEventCreator = changeEventCreator;
        this.streamingReceiver = new StreamingChangeRecordReceiver();
        this.emitTombstonesOnDelete = connectorConfig.isEmitTombstoneOnDelete();
        this.inconsistentSchemaHandler = inconsistentSchemaHandler != null ? inconsistentSchemaHandler : this::errorOnMissingSchema;
        this.skippedOperations = connectorConfig.getSkippedOperations();
        this.neverSkip = connectorConfig.supportsOperationFiltering() || this.skippedOperations.isEmpty();

        this.transactionMonitor = new TransactionMonitor(connectorConfig, metadataProvider, schemaNameAdjuster,
                this::enqueueTransactionMessage, topicNamingStrategy.transactionTopic());
        this.signalProcessor = signalProcessor;
        if (signalProcessor != null) {
            this.sourceSignalChannel = signalProcessor.getSignalChannel(SourceSignalChannel.class);
            this.sourceSignalChannel.init(connectorConfig);
        }
        else {
            this.sourceSignalChannel = null;
        }
        this.heartbeat = heartbeat;

        schemaChangeKeySchema = SchemaFactory.get().schemaHistoryConnectorKeySchema(schemaNameAdjuster, connectorConfig);

        schemaChangeValueSchema = SchemaFactory.get().schemaHistoryConnectorValueSchema(schemaNameAdjuster, connectorConfig, tableChangesSerializer);

        postProcessorRegistry = connectorConfig.getServiceRegistry().tryGetService(PostProcessorRegistry.class);
    }

    public EventDispatcher(CommonConnectorConfig connectorConfig, TopicNamingStrategy<T> topicNamingStrategy,
                           DatabaseSchema<T> schema, ChangeEventQueue<DataChangeEvent> queue, DataCollectionFilter<T> filter,
                           ChangeEventCreator changeEventCreator, InconsistentSchemaHandler<P, T> inconsistentSchemaHandler, Heartbeat heartbeat,
                           SchemaNameAdjuster schemaNameAdjuster, TransactionMonitor transactionMonitor,
                           SignalProcessor<P, ?> signalProcessor, DebeziumHeaderProducer debeziumHeaderProducer) {
        this.tableChangesSerializer = new ConnectTableChangeSerializer(schemaNameAdjuster);
        this.connectorConfig = connectorConfig;
        this.topicNamingStrategy = topicNamingStrategy;
        this.schema = schema;
        this.historizedSchema = schema.isHistorized() ? (HistorizedDatabaseSchema<T>) schema : null;
        this.queue = queue;
        this.filter = filter;
        this.changeEventCreator = changeEventCreator;
        this.debeziumHeaderProducer = debeziumHeaderProducer;
        this.streamingReceiver = new StreamingChangeRecordReceiver();
        this.emitTombstonesOnDelete = connectorConfig.isEmitTombstoneOnDelete();
        this.inconsistentSchemaHandler = inconsistentSchemaHandler != null ? inconsistentSchemaHandler : this::errorOnMissingSchema;
        this.skippedOperations = connectorConfig.getSkippedOperations();
        this.neverSkip = connectorConfig.supportsOperationFiltering() || this.skippedOperations.isEmpty();
        this.transactionMonitor = transactionMonitor;
        this.signalProcessor = signalProcessor;
        if (signalProcessor != null) {
            this.sourceSignalChannel = signalProcessor.getSignalChannel(SourceSignalChannel.class);
            this.sourceSignalChannel.init(connectorConfig);
        }
        else {
            this.sourceSignalChannel = null;
        }
        this.heartbeat = heartbeat;
        schemaChangeKeySchema = SchemaFactory.get().schemaHistoryConnectorKeySchema(schemaNameAdjuster, connectorConfig);
        schemaChangeValueSchema = SchemaFactory.get().schemaHistoryConnectorValueSchema(schemaNameAdjuster, connectorConfig, tableChangesSerializer);
        postProcessorRegistry = connectorConfig.getServiceRegistry().tryGetService(PostProcessorRegistry.class);
    }

    public void dispatchSnapshotEvent(P partition, T dataCollectionId, ChangeRecordEmitter<P> changeRecordEmitter,
                                      SnapshotReceiver<P> receiver)
            throws InterruptedException {

        try {
            // TODO Handle Heartbeat
            DataCollectionSchema dataCollectionSchema = schema.schemaFor(dataCollectionId);

            // TODO handle as per inconsistent schema info option
            if (dataCollectionSchema == null) {
                errorOnMissingSchema(partition, dataCollectionId, changeRecordEmitter);
            }

            changeRecordEmitter.emitChangeRecords(dataCollectionSchema, new Receiver<P>() {

                @Override
                public void changeRecord(P partition,
                                         DataCollectionSchema schema,
                                         Operation operation,
                                         Object key, Struct value,
                                         OffsetContext offset,
                                         ConnectHeaders headers)
                        throws InterruptedException {
                    LOGGER.trace("Received change record {} for {} operation on key {} with context {}", maybeRedactSensitiveData(value), operation,
                            maybeRedactSensitiveData(key), offset);

                    var extendedHeaders = getExtendedHeaders(headers);

                    eventListener.onEvent(partition, dataCollectionSchema.id(), offset, key, value, operation);
                    receiver.changeRecord(partition, dataCollectionSchema, operation, key, value, offset, extendedHeaders);
                }
            });
        }
        catch (Exception e) {
            handleEventProcessingFailure(e, changeRecordEmitter.getOffset());
        }
    }

    public SnapshotReceiver<P> getSnapshotChangeEventReceiver() {
        return new BufferingSnapshotChangeRecordReceiver(connectorConfig.getSnapshotMaxThreads() > 1);
    }

    public SnapshotReceiver<P> getIncrementalSnapshotChangeEventReceiver(DataChangeEventListener<P> dataListener) {
        return new IncrementalSnapshotChangeRecordReceiver(dataListener);
    }

    /**
     * Dispatches one or more {@link DataChangeEvent}s. If the given data collection is included in the currently
     * captured set of collections, the given emitter will be invoked, so it can emit one or more events (in the common
     * case, one event will be emitted, but e.g. in case of PK updates, it may be a deletion and a creation event). The
     * receiving coordinator creates {@link SourceRecord}s for all emitted events and passes them to this dispatcher's
     * {@link ChangeEventCreator} for converting them into data change events.
     *
     * @return {@code true} if an event was dispatched (i.e. sent to the message broker), {@code false} otherwise.
     */
    public boolean dispatchDataChangeEvent(P partition, T dataCollectionId, ChangeRecordEmitter<P> changeRecordEmitter) throws InterruptedException {
        try {
            boolean handled = false;
            if (changeRecordEmitter.ignoreRecord() || !filter.isIncluded(dataCollectionId)) {
                LOGGER.trace("Filtered data change event for {}", dataCollectionId);
                eventListener.onFilteredEvent(partition, "source = " + dataCollectionId, changeRecordEmitter.getOperation());
                dispatchFilteredEvent(changeRecordEmitter.getPartition(), changeRecordEmitter.getOffset());
            }
            else {
                DataCollectionSchema dataCollectionSchema = schema.schemaFor(dataCollectionId);

                // TODO handle as per inconsistent schema info option
                if (dataCollectionSchema == null) {
                    final Optional<DataCollectionSchema> replacementSchema = inconsistentSchemaHandler.handle(partition,
                            dataCollectionId, changeRecordEmitter);
                    if (!replacementSchema.isPresent()) {
                        return false;
                    }
                    dataCollectionSchema = replacementSchema.get();
                }

                changeRecordEmitter.emitChangeRecords(dataCollectionSchema, new Receiver<P>() {

                    @Override
                    public void changeRecord(P partition,
                                             DataCollectionSchema schema,
                                             Operation operation,
                                             Object key, Struct value,
                                             OffsetContext offset,
                                             ConnectHeaders headers)
                            throws InterruptedException {

                        LOGGER.trace("Received change record {} for {} operation on key {} with "
                                + "context {}", maybeRedactSensitiveData(value), operation, maybeRedactSensitiveData(key), offset);

                        if (isASignalEventToProcess(dataCollectionId, operation) && sourceSignalChannel != null) {
                            sourceSignalChannel.process(value);

                            if (signalProcessor != null) {
                                // This is a synchronization point to immediately execute an eventual stop signal, just before emitting the CDC event
                                // in this way the offset context updated by signaling will be correctly saved
                                signalProcessor.processSourceSignal();
                            }
                        }

                        if (neverSkip || !skippedOperations.contains(operation)) {
                            transactionMonitor.dataEvent(partition, dataCollectionId, offset, key, value);
                            eventListener.onEvent(partition, dataCollectionId, offset, key, value, operation);
                            if (incrementalSnapshotChangeEventSource != null) {
                                incrementalSnapshotChangeEventSource.processMessage(partition, dataCollectionId, key, offset);
                            }
                            streamingReceiver.changeRecord(partition, schema, operation, key, value, offset, headers);
                        }
                    }

                    private boolean isASignalEventToProcess(T dataCollectionId, Operation operation) {
                        return (operation == Operation.CREATE ||
                                (operation == Operation.DELETE && connectorConfig.getIncrementalSnapshotWatermarkingStrategy() == INSERT_DELETE)) &&
                                connectorConfig.isSignalDataCollection(dataCollectionId);
                    }
                });
                handled = true;
            }

            dispatchHeartbeatEvent(changeRecordEmitter.getPartition(), changeRecordEmitter.getOffset());

            return handled;
        }
        catch (Exception e) {
            handleEventProcessingFailure(e, changeRecordEmitter.getOffset());
            return false;
        }
    }

    private void handleEventProcessingFailure(Exception e, OffsetContext offsetContext) {
        switch (connectorConfig.getEventProcessingFailureHandlingMode()) {
            case FAIL:
                throw new ConnectException("Error while processing event at offset " + offsetContext.getOffset(), e);
            case WARN:
                LOGGER.warn(
                        "Error while processing event at offset {}", offsetContext.getOffset(), e);
                break;
            case SKIP:
                LOGGER.debug(
                        "Error while processing event at offset {}", offsetContext.getOffset(), e);
                break;
            default:
                LOGGER.debug(
                        "Error while processing event with EventProcessingFailureHandlingMode not supported: {}",
                        connectorConfig.getEventConvertingFailureHandlingMode(), e);
                break;
        }
    }

    public void dispatchFilteredEvent(P partition, OffsetContext offset) throws InterruptedException {
        if (incrementalSnapshotChangeEventSource != null) {
            incrementalSnapshotChangeEventSource.processFilteredEvent(partition, offset);
        }
    }

    public void dispatchTransactionCommittedEvent(P partition, OffsetContext offset, Instant timestamp) throws InterruptedException {
        transactionMonitor.transactionComittedEvent(partition, offset, timestamp);
        if (incrementalSnapshotChangeEventSource != null) {
            incrementalSnapshotChangeEventSource.processTransactionCommittedEvent(partition, offset);
        }
    }

    public void dispatchTransactionStartedEvent(P partition, String transactionId, OffsetContext offset, Instant timestamp) throws InterruptedException {
        dispatchTransactionStartedEvent(partition, new DefaultTransactionInfo(transactionId), offset, timestamp);
    }

    public void dispatchTransactionStartedEvent(P partition, TransactionInfo transactionInfo, OffsetContext offset, Instant timestamp) throws InterruptedException {
        transactionMonitor.transactionStartedEvent(partition, transactionInfo, offset, timestamp);
        if (incrementalSnapshotChangeEventSource != null) {
            incrementalSnapshotChangeEventSource.processTransactionStartedEvent(partition, offset);
        }
    }

    public void dispatchConnectorEvent(P partition, ConnectorEvent event) {
        eventListener.onConnectorEvent(partition, event);
    }

    public Optional<DataCollectionSchema> errorOnMissingSchema(P partition, T dataCollectionId, ChangeRecordEmitter<P> changeRecordEmitter) {
        eventListener.onErroneousEvent(partition, "source = " + dataCollectionId, changeRecordEmitter.getOperation());
        throw new IllegalArgumentException("No metadata registered for captured table " + dataCollectionId);
    }

    public Optional<DataCollectionSchema> ignoreMissingSchema(T dataCollectionId, ChangeRecordEmitter<P> changeRecordEmitter) {
        return Optional.empty();
    }

    public void dispatchSchemaChangeEvent(P partition, OffsetContext offsetContext, T dataCollectionId, SchemaChangeEventEmitter schemaChangeEventEmitter)
            throws InterruptedException {
        if (dataCollectionId != null && !filter.isIncluded(dataCollectionId)) {
            if (historizedSchema == null || historizedSchema.storeOnlyCapturedTables()) {
                LOGGER.trace("Filtering schema change event for {}", dataCollectionId);
                return;
            }
        }
        schemaChangeEventEmitter.emitSchemaChangeEvent(new SchemaChangeEventReceiver());

        if (incrementalSnapshotChangeEventSource != null) {
            incrementalSnapshotChangeEventSource.processSchemaChange(partition, offsetContext, dataCollectionId);
        }
    }

    public void dispatchSchemaChangeEvent(Collection<T> dataCollectionIds, SchemaChangeEventEmitter schemaChangeEventEmitter) throws InterruptedException {
        boolean anyNonfilteredEvent = false;
        if (dataCollectionIds == null || dataCollectionIds.isEmpty()) {
            anyNonfilteredEvent = true;
        }
        else {
            for (T dataCollectionId : dataCollectionIds) {
                if (filter.isIncluded(dataCollectionId)) {
                    anyNonfilteredEvent = true;
                    break;
                }
            }
        }
        if (!anyNonfilteredEvent) {
            if (historizedSchema == null || historizedSchema.storeOnlyCapturedTables()) {
                LOGGER.trace("Filtering schema change event for {}", dataCollectionIds);
                return;
            }
        }

        schemaChangeEventEmitter.emitSchemaChangeEvent(new SchemaChangeEventReceiver());
    }

    public void alwaysDispatchHeartbeatEvent(P partition, OffsetContext offset) throws InterruptedException {
        heartbeat.emit(partition.getSourcePartition(), offset);
    }

    @Deprecated
    public void dispatchHeartbeatEvent(P partition, OffsetContext offset) throws InterruptedException {
        if (heartbeat instanceof ScheduledHeartbeat scheduledHeartbeat) {
            scheduledHeartbeat.emitWithDelay(partition.getSourcePartition(), offset);
        }
        heartbeat.heartbeat(partition.getSourcePartition(), offset.getOffset(), record -> queue.enqueue(new DataChangeEvent(record)));
    }

    // Use this method when you want to dispatch the heartbeat also to incremental snapshot.
    // Currently, this is used by PostgreSQL for read-only incremental snapshot but doesn't suites well for
    // MySQL since the dispatchHeartbeatEvent is called at every received message and not when there is no message from the DB log.
    public void dispatchHeartbeatEventAlsoToIncrementalSnapshot(P partition, OffsetContext offset) throws InterruptedException {
        dispatchHeartbeatEvent(partition, offset);

        if (incrementalSnapshotChangeEventSource != null) {
            incrementalSnapshotChangeEventSource.processHeartbeat(partition, offset);
        }
    }

    public boolean heartbeatsEnabled() {
        return heartbeat.isEnabled();
    }

    private void enqueueTransactionMessage(SourceRecord record) throws InterruptedException {
        queue.enqueue(new DataChangeEvent(record));
    }

    private void enqueueSchemaChangeMessage(SourceRecord record) throws InterruptedException {
        queue.enqueue(new DataChangeEvent(record));
    }

    public void dispatchServerHeartbeatEvent(P partition, OffsetContext offset) throws InterruptedException {
        if (incrementalSnapshotChangeEventSource != null) {
            incrementalSnapshotChangeEventSource.processHeartbeat(partition, offset);
        }
    }

    public void enqueueNotification(SourceRecord record) throws InterruptedException {

        queue.enqueue(new DataChangeEvent(record));

        if (queue.isBuffered()) {
            queue.flushBuffer(Function.identity());
        }
    }

    /**
     * Change record receiver used during snapshotting. Allows for a deferred submission of records, which is needed in
     * order to set the "snapshot completed" offset field, which we can't send to Kafka Connect without sending an
     * actual record
     */
    public interface SnapshotReceiver<P extends Partition> extends ChangeRecordEmitter.Receiver<P> {
        void completeSnapshot() throws InterruptedException;
    }

    private final class StreamingChangeRecordReceiver implements ChangeRecordEmitter.Receiver<P> {

        @Override
        public void changeRecord(P partition,
                                 DataCollectionSchema dataCollectionSchema,
                                 Operation operation,
                                 Object key, Struct value,
                                 OffsetContext offsetContext,
                                 ConnectHeaders headers)
                throws InterruptedException {

            Objects.requireNonNull(value, "value must not be null");

            LOGGER.trace("Received change record {} for {} operation on key {} with context {}", maybeRedactSensitiveData(value), operation,
                    maybeRedactSensitiveData(key), offsetContext);

            // Truncate events must have null key schema as they are sent to table topics without keys
            Schema keySchema = (key == null && operation == Operation.TRUNCATE) ? null
                    : dataCollectionSchema.keySchema();
            String topicName = topicNamingStrategy.dataChangeTopic((T) dataCollectionSchema.id());

            doPostProcessing(key, value);

            var extendedHeaders = getExtendedHeaders(headers);

            SourceRecord record = new SourceRecord(partition.getSourcePartition(),
                    offsetContext.getOffset(),
                    topicName, null,
                    keySchema, key,
                    dataCollectionSchema.getEnvelopeSchema().schema(),
                    value,
                    null,
                    extendedHeaders);

            queue.enqueue(changeEventCreator.createDataChangeEvent(record));

            if (emitTombstonesOnDelete && operation == Operation.DELETE) {
                SourceRecord tombStone = record.newRecord(
                        record.topic(),
                        record.kafkaPartition(),
                        record.keySchema(),
                        record.key(),
                        null, // value schema
                        null, // value
                        record.timestamp(),
                        record.headers());

                queue.enqueue(changeEventCreator.createDataChangeEvent(tombStone));
            }
        }
    }

    private ConnectHeaders getExtendedHeaders(ConnectHeaders headers) {

        if (!connectorConfig.isExtendedHeadersEnabled()) {
            return headers;
        }

        var extendedHeaders = new ConnectHeaders(headers);
        StreamSupport.stream(debeziumHeaderProducer.contextHeaders().spliterator(), false)
                .forEach(extendedHeaders::add);
        return extendedHeaders;
    }

    private final class BufferingSnapshotChangeRecordReceiver implements SnapshotReceiver<P> {

        private AtomicReference<BufferedDataChangeEvent> bufferedEventRef = new AtomicReference<>(BufferedDataChangeEvent.NULL);
        private final boolean threaded;

        BufferingSnapshotChangeRecordReceiver(boolean threaded) {
            this.threaded = threaded;
        }

        @Override
        public void changeRecord(P partition,
                                 DataCollectionSchema dataCollectionSchema,
                                 Operation operation,
                                 Object key, Struct value,
                                 OffsetContext offsetContext,
                                 ConnectHeaders headers)
                throws InterruptedException {
            Objects.requireNonNull(value, "value must not be null");

            LOGGER.trace("Received change record for {} operation on key {}", operation, maybeRedactSensitiveData(key));

            doPostProcessing(key, value);

            SourceRecord record = new SourceRecord(
                    partition.getSourcePartition(),
                    offsetContext.getOffset(),
                    topicNamingStrategy.dataChangeTopic((T) dataCollectionSchema.id()),
                    null,
                    dataCollectionSchema.keySchema(),
                    key,
                    dataCollectionSchema.getEnvelopeSchema().schema(),
                    value,
                    null,
                    headers);

            BufferedDataChangeEvent nextBufferedEvent = new BufferedDataChangeEvent();
            nextBufferedEvent.offsetContext = offsetContext;
            nextBufferedEvent.dataChangeEvent = new DataChangeEvent(record);

            if (threaded) {
                // This entire step needs to happen atomically when using buffering with multiple threads.
                // This guarantees that the getAndSet and the enqueue do not cause a dispatch of out-of-order
                // events within a single thread.
                synchronized (queue) {
                    queue.enqueue(bufferedEventRef.getAndSet(nextBufferedEvent).dataChangeEvent);
                }
            }
            else {
                queue.enqueue(bufferedEventRef.getAndSet(nextBufferedEvent).dataChangeEvent);
            }
        }

        @Override
        public void completeSnapshot() throws InterruptedException {
            // It is possible that the last snapshotted table was empty
            // this way we ensure that the last event is always marked as last
            // even if it originates form non-last table
            final BufferedDataChangeEvent bufferedEvent = bufferedEventRef.getAndSet(BufferedDataChangeEvent.NULL);
            DataChangeEvent event = bufferedEvent.dataChangeEvent;
            if (event != null) {
                SourceRecord record = event.getRecord();
                final Struct envelope = (Struct) record.value();
                if (envelope.schema().field(Envelope.FieldName.SOURCE) != null) {
                    final Struct source = envelope.getStruct(Envelope.FieldName.SOURCE);
                    SnapshotRecord.LAST.toSource(source);
                }
                @SuppressWarnings("unchecked")
                final Map<String, Object> offset = (Map<String, Object>) record.sourceOffset();
                offset.clear();
                offset.putAll(bufferedEvent.offsetContext.getOffset());
                queue.enqueue(event);
            }
        }
    }

    private static final class BufferedDataChangeEvent {
        private static final BufferedDataChangeEvent NULL = new BufferedDataChangeEvent();

        private DataChangeEvent dataChangeEvent;
        private OffsetContext offsetContext;

    }

    private final class IncrementalSnapshotChangeRecordReceiver implements SnapshotReceiver<P> {

        public final DataChangeEventListener<P> dataListener;

        IncrementalSnapshotChangeRecordReceiver(DataChangeEventListener<P> dataListener) {
            this.dataListener = dataListener;
        }

        @Override
        public void changeRecord(P partition,
                                 DataCollectionSchema dataCollectionSchema,
                                 Operation operation,
                                 Object key, Struct value,
                                 OffsetContext offsetContext,
                                 ConnectHeaders headers)
                throws InterruptedException {
            Objects.requireNonNull(value, "value must not be null");

            LOGGER.trace("Received change record for {} operation on key {}", operation, maybeRedactSensitiveData(key));

            Schema keySchema = dataCollectionSchema.keySchema();
            String topicName = topicNamingStrategy.dataChangeTopic((T) dataCollectionSchema.id());

            doPostProcessing(key, value);

            var extendedHeaders = getExtendedHeaders(headers);

            SourceRecord record = new SourceRecord(
                    partition.getSourcePartition(),
                    offsetContext.getOffset(),
                    topicName, null,
                    keySchema, key,
                    dataCollectionSchema.getEnvelopeSchema().schema(), value,
                    null, extendedHeaders);
            dataListener.onEvent(partition, dataCollectionSchema.id(), offsetContext, keySchema, value, operation);
            queue.enqueue(changeEventCreator.createDataChangeEvent(record));
        }

        @Override
        public void completeSnapshot() {
        }
    }

    private final class SchemaChangeEventReceiver implements SchemaChangeEventEmitter.Receiver {

        private Struct schemaChangeRecordKey(SchemaChangeEvent event) {
            Struct result = new Struct(schemaChangeKeySchema);
            result.put(Fields.DATABASE_NAME, event.getDatabase());
            return result;
        }

        private Struct schemaChangeRecordValue(SchemaChangeEvent event) {
            Struct result = new Struct(schemaChangeValueSchema);
            result.put(Fields.SOURCE, event.getSource());
            result.put(Fields.TIMESTAMP, event.getTimestamp().toEpochMilli());
            result.put(Fields.DATABASE_NAME, event.getDatabase());
            result.put(Fields.SCHEMA_NAME, event.getSchema());
            result.put(Fields.DDL_STATEMENTS, event.getDdl());
            result.put(Fields.TABLE_CHANGES, tableChangesSerializer.serialize(event.getTableChanges()));
            return result;
        }

        @Override
        public void schemaChangeEvent(SchemaChangeEvent event) throws InterruptedException {
            if (historizedSchema != null) {
                historizedSchema.applySchemaChange(event);
            }

            if (connectorConfig.isSchemaChangesHistoryEnabled()) {
                final String topicName = topicNamingStrategy.schemaChangeTopic();
                final Integer partition = 0;
                final Struct key = schemaChangeRecordKey(event);
                final Struct value = schemaChangeRecordValue(event);

                var extendedHeaders = getExtendedHeaders(new ConnectHeaders());

                final SourceRecord record = new SourceRecord(event.getPartition(), event.getOffset(), topicName, partition,
                        schemaChangeKeySchema, key, schemaChangeValueSchema, value, null, extendedHeaders);
                enqueueSchemaChangeMessage(record);
            }
        }
    }

    /**
     * Provide a listener that is invoked for every incoming event to be processed.
     *
     * @param eventListener
     */
    public void setEventListener(DataChangeEventListener<P> eventListener) {
        this.eventListener = eventListener;
    }

    /**
     * Enable support for incremental snapshotting.
     */
    public void setIncrementalSnapshotChangeEventSource(Optional<IncrementalSnapshotChangeEventSource<P, ? extends DataCollectionId>> incrementalSnapshotChangeEventSource) {
        this.incrementalSnapshotChangeEventSource = (IncrementalSnapshotChangeEventSource<P, T>) incrementalSnapshotChangeEventSource.orElse(null);
    }

    /**
     * Reaction to an incoming change event for which schema is not found
     */
    @FunctionalInterface
    public interface InconsistentSchemaHandler<P extends Partition, T extends DataCollectionId> {

        /**
         * @return collection schema if the schema was updated and event can be processed, {@code empty} to skip the processing
         */
        Optional<DataCollectionSchema> handle(P partition, T dataCollectionId, ChangeRecordEmitter changeRecordEmitter);
    }

    public DatabaseSchema<T> getSchema() {
        return schema;
    }

    public HistorizedDatabaseSchema<T> getHistorizedSchema() {
        return historizedSchema;
    }

    public IncrementalSnapshotChangeEventSource<P, T> getIncrementalSnapshotChangeEventSource() {
        return incrementalSnapshotChangeEventSource;
    }

    @Override
    public void close() {
        if (heartbeatsEnabled()) {
            heartbeat.close();
        }
    }

    @SuppressWarnings("resource")
    protected void doPostProcessing(Object key, Struct value) {
        if (postProcessorRegistry != null) {
            for (PostProcessor processor : postProcessorRegistry.getProcessors()) {
                processor.apply(key, value);
            }
        }
    }
}
