package ai.devflow.event;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class RunEventPublisherTest {

    @Test
    void subscribeReturnsAnEmitterForTheRun() {
        var publisher = new RunEventPublisher();
        SseEmitter emitter = publisher.subscribe("run-1");
        assertThat(emitter).isNotNull();
        assertThat(publisher.isSubscribed("run-1")).isTrue();
    }

    @Test
    void publishingToAnUnknownRunIsSilentlyIgnored() {
        var publisher = new RunEventPublisher();
        // No subscriber yet — must not throw. The orchestrator runs whether or
        // not anyone is watching.
        assertThatCode(() -> publisher.publish("nobody-home", RunEvent.of("step", "hi")))
                .doesNotThrowAnyException();
    }

    @Test
    void completeRemovesTheSubscription() {
        var publisher = new RunEventPublisher();
        publisher.subscribe("run-2");
        publisher.complete("run-2");
        assertThat(publisher.isSubscribed("run-2")).isFalse();
    }

    @Test
    void aFailingEmitterIsDroppedRatherThanBreakingTheRun() {
        var publisher = new RunEventPublisher();
        var attempts = new AtomicInteger();
        // An emitter whose send() always throws simulates a client that
        // disconnected mid-run.
        SseEmitter broken = new SseEmitter() {
            @Override
            public void send(SseEventBuilder builder) throws java.io.IOException {
                attempts.incrementAndGet();
                throw new java.io.IOException("client gone");
            }
        };
        publisher.register("run-3", broken);

        assertThatCode(() -> publisher.publish("run-3", RunEvent.of("step", "one")))
                .doesNotThrowAnyException();
        assertThat(attempts.get()).isEqualTo(1);
        // Dropped after the first failure — no repeated attempts on a dead client.
        assertThat(publisher.isSubscribed("run-3")).isFalse();

        publisher.publish("run-3", RunEvent.of("step", "two"));
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void eventCarriesTypeMessageAndData() {
        var event = RunEvent.of("gate", "about to build", Map.of("gate", "BEFORE_BUILD"));
        assertThat(event.type()).isEqualTo("gate");
        assertThat(event.message()).isEqualTo("about to build");
        assertThat(event.data()).containsEntry("gate", "BEFORE_BUILD");
    }

    @Test
    void eventWithoutDataHasAnEmptyMap() {
        assertThat(RunEvent.of("step", "hi").data()).isEmpty();
    }
}
