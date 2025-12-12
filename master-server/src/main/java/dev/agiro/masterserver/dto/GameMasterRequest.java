package dev.agiro.masterserver.dto;

import lombok.Data;

import java.util.List;

@Data
public class GameMasterRequest {
    private String prompt;
    private String tokenId;
    private String tokenName;
    private List<AbilityDto> abilities;
    private WorldStateDto worldState;

    public GameMasterRequest() {}

}

