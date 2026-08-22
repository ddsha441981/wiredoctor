/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.boot.context.metrics.buffering.StartupTimeline;
import org.springframework.core.metrics.StartupStep;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link WireDoctorBufferingApplicationStartup}: verifies that
 * the {@code threadName} tag is captured at step creation time.
 */
class WireDoctorBufferingApplicationStartupTest {

    private final WireDoctorBufferingApplicationStartup startup =
            new WireDoctorBufferingApplicationStartup(4096);

    @Test
    void beanInstantiateStepGetsThreadNameTag() {
        StartupStep step = startup.start("spring.beans.instantiate");
        step.tag("beanName", "myBean");
        step.end();

        StartupTimeline timeline = startup.getBufferedTimeline();
        assertThat(timeline.getEvents()).hasSize(1);

        StartupTimeline.TimelineEvent event = timeline.getEvents().get(0);
        assertThat(event.getStartupStep().getName()).isEqualTo("spring.beans.instantiate");

        String threadName = null;
        for (StartupStep.Tag tag : event.getStartupStep().getTags()) {
            if ("threadName".equals(tag.getKey())) {
                threadName = tag.getValue();
                break;
            }
        }

        assertThat(threadName).isNotNull();
        assertThat(threadName).isEqualTo(Thread.currentThread().getName());
    }

    @Test
    void nonBeanInstantiateStepDoesNotGetThreadNameTag() {
        StartupStep step = startup.start("some.other.step");
        step.tag("key", "value");
        step.end();

        StartupTimeline timeline = startup.getBufferedTimeline();
        assertThat(timeline.getEvents()).hasSize(1);

        boolean hasThreadTag = false;
        for (StartupStep.Tag tag : timeline.getEvents().get(0).getStartupStep().getTags()) {
            if ("threadName".equals(tag.getKey())) {
                hasThreadTag = true;
                break;
            }
        }

        assertThat(hasThreadTag).isFalse();
    }
}
