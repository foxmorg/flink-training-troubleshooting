package com.ververica.flinktraining.solutions.troubleshoot;

import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.metrics.Counter;
import org.apache.flink.runtime.metrics.DescriptiveStatisticsHistogram;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.AssignerWithPeriodicWatermarks;
import org.apache.flink.streaming.api.functions.sink.DiscardingSink;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ververica.flinktraining.provided.troubleshoot.FakeKafkaRecord;
import com.ververica.flinktraining.provided.troubleshoot.WindowedMeasurements;
import com.ververica.flinktraining.provided.troubleshoot.SourceUtils;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static com.ververica.flinktraining.exercises.troubleshoot.TroubledStreamingJobUtils.createConfiguredEnvironment;

public class TroubledStreamingJobSolution31 {

    public static void main(String[] args) throws Exception {
        ParameterTool parameters = ParameterTool.fromArgs(args);

        final boolean local = parameters.getBoolean("local", false);

        StreamExecutionEnvironment env = createConfiguredEnvironment(parameters, local);

        //Time Characteristics
        env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
        env.getConfig().setAutoWatermarkInterval(100);

        //Checkpointing Configuration
        env.enableCheckpointing(5000);
        env.getCheckpointConfig().setMinPauseBetweenCheckpoints(4000);

        DataStream<JsonNode> sourceStream = env
                .addSource(SourceUtils.createFakeKafkaSource())
                .name("FakeKafkaSource")
                .uid("FakeKafkaSource")
                .assignTimestampsAndWatermarks(
                        new MeasurementTSExtractor(Time.of(250, TimeUnit.MILLISECONDS),
                                Time.of(1, TimeUnit.SECONDS)))
                .name("Watermarks")
                .uid("Watermarks")
                .flatMap(new MeasurementDeserializer())
                .name("Deserialization")
                .uid("Deserialization");

        OutputTag<JsonNode> lateDataTag = new OutputTag<JsonNode>("late-data") {
            private static final long serialVersionUID = 33513631677208956L;
        };

        SingleOutputStreamOperator<WindowedMeasurements> aggregatedPerLocation = sourceStream
                .keyBy(jsonNode -> jsonNode.get("location").asText())
                .timeWindow(Time.of(1, TimeUnit.SECONDS))
                .sideOutputLateData(lateDataTag)
                .process(new MeasurementWindowAggregatingFunction())
                .name("WindowedAggregationPerLocation")
                .uid("WindowedAggregationPerLocation");

        if (local) {
            aggregatedPerLocation.print()
                    .name("NormalOutput")
                    .uid("NormalOutput")
                    .disableChaining();
            aggregatedPerLocation.getSideOutput(lateDataTag).printToErr()
                    .name("LateDataSink")
                    .uid("LateDataSink")
                    .disableChaining();
        } else {
            aggregatedPerLocation.addSink(new DiscardingSink<>())
                    .name("NormalOutput")
                    .uid("NormalOutput")
                    .disableChaining();
            aggregatedPerLocation.getSideOutput(lateDataTag).addSink(new DiscardingSink<>())
                    .name("LateDataSink")
                    .uid("LateDataSink")
                    .disableChaining();
        }

        env.execute(TroubledStreamingJobSolution31.class.getSimpleName());
    }

    /**
     * Deserializes the JSON Kafka message.
     */
    public static class MeasurementDeserializer extends RichFlatMapFunction<FakeKafkaRecord, JsonNode> {
        private static final long serialVersionUID = 2L;

        private Counter numInvalidRecords;

        @Override
        public void open(final Configuration parameters) throws Exception {
            super.open(parameters);
            numInvalidRecords = getRuntimeContext().getMetricGroup().counter("numInvalidRecords");
        }

        @Override
        public void flatMap(final FakeKafkaRecord kafkaRecord, final Collector<JsonNode> out) {
            final JsonNode node;
            try {
                node = deserialize(kafkaRecord.getValue());
            } catch (IOException e) {
                numInvalidRecords.inc();
                return;
            }
            out.collect(node);
        }

        private JsonNode deserialize(final byte[] bytes) throws IOException {
            return ObjectMapperSingleton.getInstance().readValue(bytes, JsonNode.class);
        }
    }

    public static class MeasurementTSExtractor implements AssignerWithPeriodicWatermarks<FakeKafkaRecord> {
        private static final long serialVersionUID = 2L;

        private long currentMaxTimestamp;
        private long lastEmittedWatermark = Long.MIN_VALUE;
        private long lastRecordProcessingTime;

        private final long maxOutOfOrderness;
        private final long idleTimeout;

        MeasurementTSExtractor(Time maxOutOfOrderness, Time idleTimeout) {
            if (maxOutOfOrderness.toMilliseconds() < 0) {
                throw new RuntimeException("Tried to set the maximum allowed " +
                        "lateness to " + maxOutOfOrderness +
                        ". This parameter cannot be negative.");
            }

            if (idleTimeout.toMilliseconds() < 0) {
                throw new RuntimeException("Tried to set the idle Timeout" + idleTimeout +
                        ". This parameter cannot be negative.");
            }


            this.maxOutOfOrderness = maxOutOfOrderness.toMilliseconds();
            this.idleTimeout = idleTimeout.toMilliseconds();
            this.currentMaxTimestamp = Long.MIN_VALUE;
        }

        public long getMaxOutOfOrdernessInMillis() {
            return maxOutOfOrderness;
        }

        @Override
        public final Watermark getCurrentWatermark() {

            // if last record was processed more than the idleTimeout in the past, consider this
            // source idle and set timestamp to current processing time
            long currentProcessingTime = System.currentTimeMillis();
            if (lastRecordProcessingTime < currentProcessingTime - idleTimeout) {
                this.currentMaxTimestamp = currentProcessingTime;
            }

            long potentialWM = this.currentMaxTimestamp - maxOutOfOrderness;
            if (potentialWM >= lastEmittedWatermark) {
                lastEmittedWatermark = potentialWM;
            }
            return new Watermark(lastEmittedWatermark);
        }

        @Override
        public final long extractTimestamp(FakeKafkaRecord element, long previousElementTimestamp) {
            lastRecordProcessingTime = System.currentTimeMillis();
            long timestamp = element.getTimestamp();
            if (timestamp > currentMaxTimestamp) {
                currentMaxTimestamp = timestamp;
            }
            return timestamp;
        }
    }

    public static class MeasurementWindowAggregatingFunction
            extends ProcessWindowFunction<JsonNode, WindowedMeasurements, String, TimeWindow> {
        private static final long serialVersionUID = 1L;

        private static final int    EVENT_TIME_LAG_WINDOW_SIZE = 10_000;

        private transient DescriptiveStatisticsHistogram eventTimeLag;

        MeasurementWindowAggregatingFunction() {
        }

        @Override
        public void process(
                final String location,
                final Context context,
                final Iterable<JsonNode> input,
                final Collector<WindowedMeasurements> out) {

            WindowedMeasurements aggregate = new WindowedMeasurements();
            for (JsonNode record : input) {
                double result = Double.parseDouble(record.get("value").asText());
                aggregate.setSumPerWindow(aggregate.getSumPerWindow() + result);
                aggregate.setEventsPerWindow(aggregate.getEventsPerWindow() + 1);
            }

            final TimeWindow window = context.window();
            aggregate.setWindowStart(window.getStart());
            aggregate.setWindowEnd(window.getEnd());
            aggregate.setLocation(location);

            eventTimeLag.update(System.currentTimeMillis() - window.getEnd());
            out.collect(aggregate);
        }

        @Override
        public void open(Configuration parameters) throws Exception {
            super.open(parameters);

            eventTimeLag = getRuntimeContext().getMetricGroup().histogram("eventTimeLag",
                    new DescriptiveStatisticsHistogram(EVENT_TIME_LAG_WINDOW_SIZE));
        }
    }

    private static class ObjectMapperSingleton {
        static ObjectMapper getInstance() {
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            return objectMapper;
        }
    }
}
