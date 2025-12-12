package dev.agiro.masterserver.dto;

import java.util.List;
import java.util.Map;

public class GameMasterResponse {
    private String narration;
    private List<ActionDto> actions;
    private String selectedAbilityId;
    private String selectedAbilityName;
    private String reasoning;

    public GameMasterResponse() {}

    public GameMasterResponse(String narration, List<ActionDto> actions) {
        this.narration = narration;
        this.actions = actions;
    }

    public String getNarration() {
        return narration;
    }

    public void setNarration(String narration) {
        this.narration = narration;
    }

    public List<ActionDto> getActions() {
        return actions;
    }

    public void setActions(List<ActionDto> actions) {
        this.actions = actions;
    }

    public String getSelectedAbilityId() {
        return selectedAbilityId;
    }

    public void setSelectedAbilityId(String selectedAbilityId) {
        this.selectedAbilityId = selectedAbilityId;
    }

    public String getSelectedAbilityName() {
        return selectedAbilityName;
    }

    public void setSelectedAbilityName(String selectedAbilityName) {
        this.selectedAbilityName = selectedAbilityName;
    }

    public String getReasoning() {
        return reasoning;
    }

    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
    }

    public static class ActionDto {
        private String type; // useAbility, rollAbilityCheck, rollSkillCheck, rollSavingThrow, applyDamage, moveToken, createToken
        private String tokenId;
        private String abilityId;
        private String ability; // for ability checks/saves (str, dex, con, etc.)
        private String skill;   // for skill checks
        private String target;
        private Integer amount;
        private Integer x;
        private Integer y;
        private String name;
        private String img;
        private String actorId;

        public ActionDto() {}

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getTokenId() {
            return tokenId;
        }

        public void setTokenId(String tokenId) {
            this.tokenId = tokenId;
        }

        public String getAbilityId() {
            return abilityId;
        }

        public void setAbilityId(String abilityId) {
            this.abilityId = abilityId;
        }

        public String getAbility() {
            return ability;
        }

        public void setAbility(String ability) {
            this.ability = ability;
        }

        public String getSkill() {
            return skill;
        }

        public void setSkill(String skill) {
            this.skill = skill;
        }

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }

        public Integer getAmount() {
            return amount;
        }

        public void setAmount(Integer amount) {
            this.amount = amount;
        }

        public Integer getX() {
            return x;
        }

        public void setX(Integer x) {
            this.x = x;
        }

        public Integer getY() {
            return y;
        }

        public void setY(Integer y) {
            this.y = y;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getImg() {
            return img;
        }

        public void setImg(String img) {
            this.img = img;
        }

        public String getActorId() {
            return actorId;
        }

        public void setActorId(String actorId) {
            this.actorId = actorId;
        }
    }
}

