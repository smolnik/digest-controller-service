package net.adamsmolnik.boundary.controller;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.inject.Singleton;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.google.gson.Gson;

/**
 * @author ASmolnik
 *
 */
@Singleton
public class SimpleSqsEndpoint {

    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();

    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    private final Gson json = new Gson();

    private final AmazonSQS sqs;

    public SimpleSqsEndpoint() {
        sqs = new AmazonSQSClient();
    }

    public final void handleString(Function<String, String> requestProcessor, String queueIn, Optional<String> queueOut) {
        handle(requestProcessor, (request) -> {
            return request;
        }, queueIn, queueOut);
    }

    public final void handleVoid(Consumer<String> requestProcessor, String queueIn) {
        handle((request) -> {
            requestProcessor.accept(request);
            return null;
        }, (request) -> {
            return request;
        }, queueIn, Optional.empty());
    }

    public final <T, R> void handleJson(Function<T, R> requestProcessor, Class<T> requestClass, String queueIn, String queueOut) {
        handle(requestProcessor, (request) -> {
            return json.fromJson(request, requestClass);
        }, queueIn, Optional.of(queueOut));
    }

    public final <T, R> void handle(Function<T, R> requestProcessor, Function<String, T> requestMapper, String queueIn, Optional<String> queueOut) {
        poller.scheduleAtFixedRate(() -> {
            try {
                List<Message> messages = sqs.receiveMessage(new ReceiveMessageRequest().withQueueUrl(queueIn)).getMessages();
                for (Message message : messages) {
                    T request = requestMapper.apply(message.getBody());
                    executor.submit(() -> {
                        R response = requestProcessor.apply(request);
                        if (queueOut.isPresent()) {
                            sqs.sendMessage(new SendMessageRequest(queueOut.get(), json.toJson(response)));
                        }
                    });
                    sqs.deleteMessage(new DeleteMessageRequest(queueIn, message.getReceiptHandle()));
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }

        }, 0, 10, TimeUnit.SECONDS);
    }

    public void shutdown() {
        sqs.shutdown();
        poller.shutdownNow();
        executor.shutdownNow();
    }
}
