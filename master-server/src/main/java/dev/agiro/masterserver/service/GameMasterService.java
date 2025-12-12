package dev.agiro.masterserver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.dto.AbilityDto;
import dev.agiro.masterserver.dto.GameMasterRequest;
import dev.agiro.masterserver.dto.GameMasterResponse;
import dev.agiro.masterserver.dto.GameMasterResponse.ActionDto;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class GameMasterService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT = """
            You are an AI Game Master assistant for a tabletop RPG game (like D&D 5e) running on Foundry VTT.
            Your job is to understand what action the player wants to perform and determine which ability, spell, skill, or item should be used.
            
            You will receive:
            1. A user prompt describing what they want to do
            2. The token/character name
            3. A list of available abilities (spells, weapons, features, skills, ability scores)
            4. Current world state (scene, other tokens, combat status)
            
            You must respond with a JSON object containing:
            - "narration": A brief narrative description of the action (as a Game Master would describe it)
            - "reasoning": Your reasoning for choosing this ability/action
            - "selectedAbilityId": The ID of the ability to use (from the provided list), or null if no ability matches
            - "selectedAbilityName": The name of the selected ability
            - "actions": An array of actions to execute. Each action has a "type" and related fields:
            
            Action types:
            1. "useAbility" - Use an item/spell/feature
               - tokenId: the token performing the action
               - abilityId: the ID of the ability/item to use
               
            2. "rollAbilityCheck" - Roll an ability check (STR, DEX, CON, INT, WIS, CHA)
               - tokenId: the token rolling
               - ability: lowercase ability name (str, dex, con, int, wis, cha)
               
            3. "rollSkillCheck" - Roll a skill check
               - tokenId: the token rolling
               - skill: skill key (acr, ani, arc, ath, dec, his, ins, itm, inv, med, nat, prc, prf, per, rel, slt, ste, sur)
               
            4. "rollSavingThrow" - Roll a saving throw
               - tokenId: the token rolling
               - ability: lowercase ability name (str, dex, con, int, wis, cha)
               
            5. "applyDamage" - Apply damage to a target
               - target: actor ID to damage
               - amount: damage amount
            
            IMPORTANT: 
            - language used in the response must be %s
            - Use the language and terminology of a tabletop RPG Game Master
            - Be concise but descriptive in the narration
            - Only use abilities that exist in the provided list
            - Match the user's intent to the most appropriate ability
            - If asking to attack, look for weapons or attack spells
            - If asking to cast a spell, find it in the abilities list
            - If asking to check something, determine if it's a skill check or ability check
            - Always include the tokenId from the request in your actions
            - Respond ONLY with valid JSON, no markdown or extra text
            """;

    public GameMasterService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.defaultOptions(ChatOptions.builder()
                .model("gemini-2.5-flash")
                .temperature(0.7)
                .build())
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public GameMasterResponse processRequest(GameMasterRequest request) {
        String abilitiesSummary = formatAbilities(request.getAbilities());
        String worldStateSummary = formatWorldState(request);

        String userMessage = String.format("""
                TOKEN: %s (ID: %s)
                
                USER PROMPT: %s
                
                AVAILABLE ABILITIES:
                %s
                
                WORLD STATE:
                %s
                
                Analyze the user's intent and respond with the appropriate JSON.
                """,
                request.getTokenName(),
                request.getTokenId(),
                request.getPrompt(),
                abilitiesSummary,
                worldStateSummary
        );

        GameMasterResponse aiResponse = chatClient.prompt()
                .system(SYSTEM_PROMPT.formatted("Català")) // Change language as needed
                .user(userMessage)
                .call()
                .entity(GameMasterResponse.class);

        if (aiResponse == null ) {
            GameMasterResponse fallback = new GameMasterResponse();
            fallback.setNarration("I couldn't determine what action to take. Please try rephrasing your request.");
            fallback.setActions(new ArrayList<>());
            return fallback;
        }

        return aiResponse;
    }

    private String formatAbilities(List<AbilityDto> abilities) {
        if (abilities == null || abilities.isEmpty()) {
            return "No abilities available";
        }

        return abilities.stream()
                .map(a -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append(String.format("- [%s] %s (type: %s)", a.getId(), a.getName(), a.getType()));
                    if (a.getDescription() != null && !a.getDescription().isEmpty()) {
                        String desc = a.getDescription().length() > 100
                            ? a.getDescription().substring(0, 100) + "..."
                            : a.getDescription();
                        sb.append("\n  Description: ").append(desc);
                    }
                    if (a.getActionType() != null) {
                        sb.append("\n  Action Type: ").append(a.getActionType());
                    }
                    if (a.getDamage() != null && !a.getDamage().isEmpty()) {
                        sb.append("\n  Damage: ").append(a.getDamage());
                    }
                    if (a.getLevel() != null) {
                        sb.append("\n  Level: ").append(a.getLevel());
                    }
                    if (a.getMod() != null) {
                        sb.append("\n  Modifier: ").append(a.getMod() >= 0 ? "+" : "").append(a.getMod());
                    }
                    return sb.toString();
                })
                .collect(Collectors.joining("\n"));
    }

    private String formatWorldState(GameMasterRequest request) {
        StringBuilder sb = new StringBuilder();

        if (request.getWorldState() != null) {
            var ws = request.getWorldState();
            if (ws.getSceneName() != null) {
                sb.append("Scene: ").append(ws.getSceneName()).append("\n");
            }
            if (ws.getTokens() != null && !ws.getTokens().isEmpty()) {
                sb.append("Tokens in scene:\n");
                for (var token : ws.getTokens()) {
                    sb.append(String.format("  - %s (ID: %s)", token.getName(), token.getId()));
                    if (token.getHp() != null) {
                        sb.append(String.format(" HP: %d/%d",
                            token.getHp().get("value"),
                            token.getHp().get("max")));
                    }
                    sb.append("\n");
                }
            }
            if (ws.getCombat() != null) {
                sb.append(String.format("Combat: Round %d, Turn %d\n",
                    ws.getCombat().getRound(),
                    ws.getCombat().getTurn()));
            }
        }

        return sb.toString().isEmpty() ? "No world state available" : sb.toString();
    }
}

