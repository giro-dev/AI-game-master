package dev.agiro.masterserver.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Data
public class WorldStateDto {
    private String sceneName;
    private String sceneId;
    private List<TokenInfo> tokens;
    private CombatInfo combat;
    private RollInfo lastRoll;

    @Data
    public static class TokenInfo {
        private String id;
        private String name;
        private Integer x;
        private Integer y;
        private String actorId;
        private Map<String, Integer> hp;
        private Integer disposition;
    }

    @Data
    public static class CombatInfo {
        private Integer round;
        private Integer turn;
        private String currentCombatantId;
    }

    @Data
    public static class RollInfo {
        private String messageId;
        private String actorId;
        private String actorName;
        private String speakerAlias;
        private String tokenId;
        private String flavor;
        private String formula;
        private Integer total;
        private Integer target;
        private Integer margin;
        private String outcome;
        private Boolean success;
        private String content;
        private List<DieInfo> dice;
        private Long rolledAt;
    }

    @Data
    public static class DieInfo {
        private Integer faces;
        private List<Integer> results;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        if (sceneName != null) {
            sb.append("Escena: ").append(sceneName);
            if (sceneId != null) sb.append(" (").append(sceneId).append(")");
            sb.append('\n');
        }

        if (tokens != null && !tokens.isEmpty()) {
            sb.append("Tokens a l'escena:\n");
            for (TokenInfo token : tokens) {
                sb.append("  - ").append(token.getName()).append(" [").append(token.getId()).append("]");
                if (token.getHp() != null) {
                    sb.append(" HP ")
                            .append(token.getHp().getOrDefault("value", 0))
                            .append("/")
                            .append(token.getHp().getOrDefault("max", 0));
                }
                if (token.getDisposition() != null) {
                    sb.append(" disposició=").append(token.getDisposition());
                }
                sb.append('\n');
            }
        }

        if (combat != null) {
            sb.append("Combat: ronda ")
                    .append(combat.getRound())
                    .append(", torn ")
                    .append(combat.getTurn())
                    .append(", combatent actual=")
                    .append(combat.getCurrentCombatantId())
                    .append('\n');
        }

        if (lastRoll != null) {
            sb.append("Última tirada:\n");
            sb.append("  - Actor: ")
                    .append(firstNonBlank(lastRoll.getActorName(), lastRoll.getSpeakerAlias(), "desconegut"))
                    .append('\n');
            if (lastRoll.getFlavor() != null) {
                sb.append("  - Acció/Tirada: ").append(lastRoll.getFlavor()).append('\n');
            }
            if (lastRoll.getFormula() != null) {
                sb.append("  - Fórmula: ").append(lastRoll.getFormula()).append('\n');
            }
            if (lastRoll.getTotal() != null) {
                sb.append("  - Resultat: ").append(lastRoll.getTotal()).append('\n');
            }
            if (lastRoll.getTarget() != null) {
                sb.append("  - Dificultat/Objectiu: ").append(lastRoll.getTarget()).append('\n');
            }
            if (lastRoll.getMargin() != null) {
                sb.append("  - Marge: ").append(lastRoll.getMargin()).append('\n');
            }
            if (lastRoll.getOutcome() != null) {
                sb.append("  - Outcome: ").append(lastRoll.getOutcome()).append('\n');
            } else if (lastRoll.getSuccess() != null) {
                sb.append("  - Èxit inferit: ").append(lastRoll.getSuccess() ? "sí" : "no").append('\n');
            }
            if (lastRoll.getDice() != null && !lastRoll.getDice().isEmpty()) {
                String diceSummary = lastRoll.getDice().stream()
                        .map(die -> "d" + die.getFaces() + "=" + die.getResults())
                        .collect(Collectors.joining(", "));
                sb.append("  - Daus: ").append(diceSummary).append('\n');
            }
            if (lastRoll.getContent() != null && !lastRoll.getContent().isBlank()) {
                sb.append("  - Text del xat: ").append(lastRoll.getContent()).append('\n');
            }
        }

        return sb.isEmpty() ? "No world state available" : sb.toString().trim();
    }

    private static String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) return first;
        if (second != null && !second.isBlank()) return second;
        return fallback;
    }
}
