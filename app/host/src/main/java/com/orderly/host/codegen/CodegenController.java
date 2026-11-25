package com.orderly.host.codegen;

import com.orderly.host.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Loopback-only HTTP surface for the built-in module and motion generation studio.
 *
 * <p>Mutating routes require explicit action headers so cross-site forms cannot
 * trigger generation, preview processes, version application or step rollback.</p>
 */
@RestController
@RequestMapping("/api/codegen")
public class CodegenController {

    private final CodegenService service;

    public CodegenController(CodegenService service) {
        this.service = service;
    }

    @GetMapping("/sessions")
    public List<CodegenService.SessionSummary> sessions() {
        return service.sessions();
    }

    @GetMapping("/sessions/{sessionId}")
    public CodegenService.SessionView session(@PathVariable String sessionId) {
        return service.session(sessionId);
    }

    @PostMapping("/sessions")
    public CodegenService.SessionView generate(
            @RequestHeader(name = "X-Orderly-Action", defaultValue = "") String action,
            @RequestBody CodegenService.GenerateRequest request
    ) {
        requireAction(action, "generate-codegen");
        return service.generate(request);
    }

    @PostMapping("/sessions/{sessionId}/rebuild")
    public CodegenService.SessionView rebuild(
            @PathVariable String sessionId,
            @RequestHeader(name = "X-Orderly-Action", defaultValue = "") String action
    ) {
        requireAction(action, "rebuild-codegen");
        return service.rebuild(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/preview")
    public CodegenService.SessionView preview(
            @PathVariable String sessionId,
            @RequestHeader(name = "X-Orderly-Action", defaultValue = "") String action
    ) {
        requireAction(action, "preview-codegen");
        return service.preview(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/apply")
    public CodegenService.SessionView apply(
            @PathVariable String sessionId,
            @RequestHeader(name = "X-Orderly-Action", defaultValue = "") String action
    ) {
        requireAction(action, "apply-codegen-version");
        return service.apply(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/steps/{stepId}/rollback")
    public CodegenService.SessionView rollbackStep(
            @PathVariable String sessionId,
            @PathVariable String stepId,
            @RequestHeader(name = "X-Orderly-Action", defaultValue = "") String action
    ) {
        requireAction(action, "rollback-codegen-step");
        return service.rollbackStep(sessionId, stepId);
    }

    @GetMapping("/sessions/{sessionId}/steps/{stepId}/diff")
    public CodegenService.StepDiff stepDiff(
            @PathVariable String sessionId,
            @PathVariable String stepId
    ) {
        return service.stepDiff(sessionId, stepId);
    }

    @GetMapping("/versions")
    public List<CodegenService.CodegenVersion> versions() {
        return service.versions();
    }

    private void requireAction(String supplied, String expected) {
        if (!expected.equals(supplied)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "CODEGEN_ACTION_REQUIRED",
                    "生成器操作缺少有效的 X-Orderly-Action 请求头"
            );
        }
    }
}
