package io.streamzi.router.source.http;

import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.streamzi.router.base.StrombrauBaseVerticle;
import io.vertx.core.json.Json;
import io.vertx.kafka.client.producer.RecordMetadata;
import io.vertx.reactivex.config.ConfigRetriever;
import io.vertx.reactivex.core.http.HttpServer;
import io.vertx.reactivex.core.http.HttpServerRequest;

import io.streamzi.cloudevents.impl.CloudEventImpl;
import io.streamzi.cloudevents.CloudEvent;
import io.vertx.reactivex.kafka.client.producer.KafkaProducer;
import io.vertx.reactivex.kafka.client.producer.KafkaProducerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;

import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HttpEventSourceVerticle extends StrombrauBaseVerticle {

    private static final Logger logger = Logger.getLogger(HttpEventSourceVerticle.class.getName());

    private KafkaProducer<String, String> producer;

    @Override
    public void startStromBrauVerticle(final ConfigRetriever retriever) {
        logger.info("\uD83C\uDF7A \uD83C\uDF7A Starting HTTP Ingest Verticle");

        retriever.rxGetConfig().subscribe(myconf -> {
            final Map config = new Properties();
            config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, myconf.getString("MY_CLUSTER_KAFKA_SERVICE_HOST") + ":"  + myconf.getInteger("MY_CLUSTER_KAFKA_SERVICE_PORT").toString());

            producer = KafkaProducer.create(vertx, config, String.class, String.class);
        });


        final HttpServer server = vertx.createHttpServer();
        final Flowable<HttpServerRequest> requestFlowable = server.requestStream().toFlowable();

        requestFlowable.subscribe(httpServerRequest -> {

            final Observable<CloudEvent> observable = httpServerRequest
                    .toObservable()
                    .compose(io.vertx.reactivex.core.ObservableHelper.unmarshaller(CloudEventImpl.class));

            observable.subscribe(cloudEvent -> {

                if (httpServerRequest.path().equals("/ce")) {
                    logger.info("Received Event-Type: " + cloudEvent.getEventType());

                    // ship it!
                    try {

                        final KafkaProducerRecord<String, String> record =
                                KafkaProducerRecord.create(cloudEvent.getEventType(), cloudEvent.getEventID(), Json.encode(cloudEvent));

                        producer.write(record, done -> {

                            if (done.succeeded()) {

                                final RecordMetadata recordMetadata = done.result();
                                logger.info("Message " + record.value() + " written on topic=" + recordMetadata.getTopic() +
                                        ", partition=" + recordMetadata.getPartition() +
                                        ", offset=" + recordMetadata.getOffset());
                            }
                        });

                    } catch (Exception e) {
                        logger.log(Level.SEVERE, "whoops", e);
                    }
                } else {
                    logger.fine("Ignoring request");
                }
            });

            // finish the incoming request, with a ACCEPT response...
            httpServerRequest.response().setChunked(true)
                    .putHeader("content-type", "text/plain")
                    .setStatusCode(201) // accepted
                    .end("Event received");
        });

        server.listen(8081);
    }
}
