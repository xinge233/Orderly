package com.orderly.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@RestController
@RequestMapping("/api")
public final class StreamController {
    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException exception) {
            emitter.completeWithError(exception);
        }
        return emitter;
    }
}
