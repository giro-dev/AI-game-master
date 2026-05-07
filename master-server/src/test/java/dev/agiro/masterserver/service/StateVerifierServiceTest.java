package dev.agiro.masterserver.service;

import dev.agiro.masterserver.dto.StateUpdateDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StateVerifierServiceTest {

    @Mock
    ChatClient.Builder chatClientBuilder;

    @Mock
    ModelRoutingService modelRoutingService;

    StateVerifierService service;

    @BeforeEach
    void setUp() {
        when(modelRoutingService.optionsFor(anyString())).thenReturn(mock(ChatOptions.class));
        when(chatClientBuilder.defaultOptions(any())).thenReturn(chatClientBuilder);
        when(chatClientBuilder.build()).thenReturn(mock(ChatClient.class));
        service = new StateVerifierService(chatClientBuilder, modelRoutingService);
    }

    @Test
    void nullUpdatesReturnsFalse() {
        assertFalse(service.hasCriticalMutations(null));
    }

    @Test
    void emptyUpdatesReturnsFalse() {
        StateUpdateDto updates = StateUpdateDto.builder().build();
        assertFalse(service.hasCriticalMutations(updates));
    }

    @Test
    void blankTransitionReturnsFalse() {
        StateUpdateDto updates = StateUpdateDto.builder().transitionTriggered("  ").build();
        assertFalse(service.hasCriticalMutations(updates));
    }

    @Test
    void transitionTriggeredReturnsTrue() {
        StateUpdateDto updates = StateUpdateDto.builder().transitionTriggered("scene-2").build();
        assertTrue(service.hasCriticalMutations(updates));
    }

    @Test
    void discoveredCluesNonEmptyReturnsTrue() {
        StateUpdateDto updates = StateUpdateDto.builder().discoveredClues(List.of("clue-1")).build();
        assertTrue(service.hasCriticalMutations(updates));
    }

    @Test
    void npcDispositionChangesNonEmptyReturnsTrue() {
        StateUpdateDto updates = StateUpdateDto.builder()
                .npcDispositionChanges(Map.of("npc-1", "hostile"))
                .build();
        assertTrue(service.hasCriticalMutations(updates));
    }

    @Test
    void tensionDeltaAloneIsNotCritical() {
        StateUpdateDto updates = StateUpdateDto.builder().tensionDelta(2).build();
        assertFalse(service.hasCriticalMutations(updates));
    }
}
