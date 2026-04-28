package com.fintech.payment.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

/**
 * RabbitMQ topology:
 *
 *  Producer ──► payment.exchange ──► payment.processing (queue)
 *                                         │  (on reject/DLX)
 *                                         ▼
 *                              payment.dead-letter.exchange
 *                                         │
 *                                         ▼
 *                              payment.dead-letter (DLQ)
 *
 * Retry queue (delayed re-delivery):
 *  payment.retry ──[TTL expiry]──► payment.exchange ──► payment.processing
 */
@Configuration
public class RabbitMQConfig {

    @Value("${app.queue.payment-processing}") private String processingQueue;
    @Value("${app.queue.payment-dlq}")         private String dlQueue;
    @Value("${app.queue.payment-retry}")       private String retryQueue;
    @Value("${app.queue.exchange}")            private String exchange;
    @Value("${app.queue.dlx}")                 private String dlExchange;
    @Value("${app.payment.retry-delay-ms}")    private long retryDelayMs;

    // ─── Exchanges ────────────────────────────────────────────────────────────

    @Bean
    public DirectExchange paymentExchange() {
        return new DirectExchange(exchange, true, false);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(dlExchange, true, false);
    }

    // ─── Queues ───────────────────────────────────────────────────────────────

    /**
     * Main processing queue.
     * Dead-letter config: rejected messages go to the DLX.
     */
    @Bean
    public Queue paymentProcessingQueue() {
        return QueueBuilder.durable(processingQueue)
            .withArgument("x-dead-letter-exchange", dlExchange)
            .withArgument("x-dead-letter-routing-key", "dead-letter")
            .build();
    }

    /**
     * Dead Letter Queue — messages that exhausted all retries land here.
     * Ops team monitors this queue for manual inspection.
     */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(dlQueue).build();
    }

    /**
     * Retry queue with TTL.
     * Messages sit here for retryDelayMs, then get re-routed to
     * the main exchange via dead-letter routing (expired TTL acts as DLX).
     */
    @Bean
    public Queue retryQueue() {
        return QueueBuilder.durable(retryQueue)
            .withArgument("x-message-ttl", retryDelayMs)
            .withArgument("x-dead-letter-exchange", exchange)
            .withArgument("x-dead-letter-routing-key", "payment")
            .build();
    }

    // ─── Bindings ─────────────────────────────────────────────────────────────

    @Bean
    public Binding processingBinding() {
        return BindingBuilder.bind(paymentProcessingQueue())
            .to(paymentExchange())
            .with("payment");
    }

    @Bean
    public Binding dlqBinding() {
        return BindingBuilder.bind(deadLetterQueue())
            .to(deadLetterExchange())
            .with("dead-letter");
    }

    @Bean
    public Binding retryBinding() {
        return BindingBuilder.bind(retryQueue())
            .to(paymentExchange())
            .with("retry");
    }

    // ─── Message converter ────────────────────────────────────────────────────

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }

    /**
     * Listener container factory with retry interceptor.
     * On exception, Spring AMQP retries 3 times with backoff,
     * then calls RejectAndDontRequeueRecoverer → message goes to DLX.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter());
        factory.setAdviceChain(retryInterceptor());
        return factory;
    }

    @Bean
    public RetryOperationsInterceptor retryInterceptor() {
        return RetryInterceptorBuilder.stateless()
            .maxAttempts(3)
            .backOffOptions(1000, 2.0, 10000)  // 1s, 2x backoff, max 10s
            .recoverer(new RejectAndDontRequeueRecoverer())
            .build();
    }
}
