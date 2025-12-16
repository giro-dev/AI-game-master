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
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class GameMasterService {

    private final ChatClient chatClient;

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
            - language used in the response must be in {language}
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

    private final String USER_MESSAGE_TEMPLATE = """
                TOKEN: {tokenName} (ID: {tokenId})
                
                USER PROMPT: {prompt}
                
                AVAILABLE ABILITIES:
                {abilities}
                
                WORLD STATE:
                {worldState}
                
                Analyze the user's intent and respond with the appropriate JSON.
                """;

    public GameMasterService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.defaultOptions(ChatOptions.builder()
                .model("gemini-2.5-flash")
                .temperature(0.7)
                .build())
                .build();
    }

    public GameMasterResponse processRequest(GameMasterRequest request) {

        Map<String, Object> userPromptParameters = Map.of(
               "tokenName", request.getTokenName(),
               "tokenId", request.getTokenId(),
                "prompt", request.getPrompt(),
                "abilities", request.getAbilities().stream().map(AbilityDto::toString).toList(),
                "worldState", request.getWorldState().toString());

        GameMasterResponse aiResponse = chatClient.prompt()
                .system(system -> system.text(SYSTEM_PROMPT)
                        .param("language", "Català"))
                .user(user -> user.text(USER_MESSAGE_TEMPLATE)
                        .params(userPromptParameters))
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



}

