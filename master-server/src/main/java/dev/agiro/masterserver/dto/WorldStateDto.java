package dev.agiro.masterserver.dto;

import java.util.Map;

public class WorldStateDto {
    private String sceneName;
    private String sceneId;
    private java.util.List<TokenInfo> tokens;
    private CombatInfo combat;

    public WorldStateDto() {}

    public String getSceneName() {
        return sceneName;
    }

    public void setSceneName(String sceneName) {
        this.sceneName = sceneName;
    }

    public String getSceneId() {
        return sceneId;
    }

    public void setSceneId(String sceneId) {
        this.sceneId = sceneId;
    }

    public java.util.List<TokenInfo> getTokens() {
        return tokens;
    }

    public void setTokens(java.util.List<TokenInfo> tokens) {
        this.tokens = tokens;
    }

    public CombatInfo getCombat() {
        return combat;
    }

    public void setCombat(CombatInfo combat) {
        this.combat = combat;
    }

    public static class TokenInfo {
        private String id;
        private String name;
        private Integer x;
        private Integer y;
        private String actorId;
        private Map<String, Integer> hp;
        private Integer disposition;

        public TokenInfo() {}

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
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

        public String getActorId() {
            return actorId;
        }

        public void setActorId(String actorId) {
            this.actorId = actorId;
        }

        public Map<String, Integer> getHp() {
            return hp;
        }

        public void setHp(Map<String, Integer> hp) {
            this.hp = hp;
        }

        public Integer getDisposition() {
            return disposition;
        }

        public void setDisposition(Integer disposition) {
            this.disposition = disposition;
        }
    }

    public static class CombatInfo {
        private Integer round;
        private Integer turn;
        private String currentCombatantId;

        public CombatInfo() {}

        public Integer getRound() {
            return round;
        }

        public void setRound(Integer round) {
            this.round = round;
        }

        public Integer getTurn() {
            return turn;
        }

        public void setTurn(Integer turn) {
            this.turn = turn;
        }

        public String getCurrentCombatantId() {
            return currentCombatantId;
        }

        public void setCurrentCombatantId(String currentCombatantId) {
            this.currentCombatantId = currentCombatantId;
        }
    }

    private String toString(GameMasterRequest request) {
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

