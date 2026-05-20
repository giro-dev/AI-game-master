package dev.agiro.masterserver.dto;

import lombok.Data;

import java.util.List;

@Data
public class GameMasterRequest {
    private String prompt;
    private String tokenId;
    private String tokenName;
    private String worldId;
    private String foundrySystem;
    private String conversationId;
    private List<AbilityDto> abilities;
    private WorldStateDto worldState;
    /**
     * RAG chunk types the requesting user is allowed to search.
     * When null or empty, all chunk types are searched (GM behaviour).
     */
    private List<String> allowedChunkTypes;

    public GameMasterRequest() {}

}

