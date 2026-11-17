package com.orderly.host.ai;

import com.orderly.host.api.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiSettingsServiceTest {

    private final AiProviderClient client = new AiProviderClient() {
        @Override
        public String complete(
                AiProviderConfig config,
                String systemPrompt,
                List<AiChatMessage> messages
        ) {
            return "OK";
        }
    };

    @Test
    void deepSeekModeUsesOfficialDefaultsAndNeverExposesKey() {
        AiSettingsService service = new AiSettingsService(client, false, new AiContextEpoch());
        AiContracts.SettingsRequest request = new AiContracts.SettingsRequest(
                AiContracts.Mode.DEEPSEEK,
                "temporary-key",
                null,
                null,
                false
        );
        AiContracts.SettingsView view = service.configure(request);

        assertThat(view.mode()).isEqualTo(AiContracts.Mode.DEEPSEEK);
        assertThat(view.baseUrl()).isEqualTo(AiSettingsService.DEEPSEEK_BASE_URL);
        assertThat(view.model()).isEqualTo(AiSettingsService.DEEPSEEK_MODEL);
        assertThat(view.configured()).isTrue();
        assertThat(view.toString()).doesNotContain("temporary-key");
        assertThat(request.toString()).doesNotContain("temporary-key");
    }

    @Test
    void customModeRequiresHttpsUnlessLocalDevelopmentIsExplicit() {
        AiSettingsService service = new AiSettingsService(client, false, new AiContextEpoch());
        assertThatThrownBy(() -> service.configure(new AiContracts.SettingsRequest(
                AiContracts.Mode.CUSTOM,
                "temporary-key",
                "http://example.com/v1",
                "custom-model",
                false
        )))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("HTTPS");

        AiSettingsService local = new AiSettingsService(client, true, new AiContextEpoch());
        AiContracts.SettingsView view = local.configure(new AiContracts.SettingsRequest(
                AiContracts.Mode.CUSTOM,
                "temporary-key",
                "http://127.0.0.1:18080/v1",
                "custom-model",
                true
        ));
        assertThat(view.configured()).isTrue();
        assertThat(view.allowDeleteDirect()).isTrue();
        assertThat(view.allowLocalProvider()).isTrue();
    }

    @Test
    void clearRemovesTemporaryCredential() {
        AiSettingsService service = new AiSettingsService(client, false, new AiContextEpoch());
        service.configure(new AiContracts.SettingsRequest(
                AiContracts.Mode.DEEPSEEK,
                "temporary-key",
                null,
                null,
                false
        ));
        service.clear();

        assertThat(service.view().configured()).isFalse();
        assertThatThrownBy(service::requireConfigured)
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("尚未接入");
    }

    @Test
    void updatingOptionsCanRetainTheExistingTemporaryKey() {
        AiContextEpoch contextEpoch = new AiContextEpoch();
        AiSettingsService service = new AiSettingsService(client, false, contextEpoch);
        service.configure(new AiContracts.SettingsRequest(
                AiContracts.Mode.DEEPSEEK,
                "temporary-key",
                null,
                null,
                false
        ));

        AiContracts.SettingsView view = service.configure(new AiContracts.SettingsRequest(
                AiContracts.Mode.DEEPSEEK,
                "",
                null,
                null,
                true
        ));

        assertThat(view.configured()).isTrue();
        assertThat(view.allowDeleteDirect()).isTrue();
        assertThat(service.requireConfigured().apiKey()).isEqualTo("temporary-key");
        assertThat(contextEpoch.current()).isEqualTo(2);
    }
}
