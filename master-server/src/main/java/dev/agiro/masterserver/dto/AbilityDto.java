package dev.agiro.masterserver.dto;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class AbilityDto {
    private String id;
    private String name;
    private String type;
    private String description;
    private String actionType;
    private List<Map<String, String>> damage;
    private Map<String, Object> range;
    private Map<String, Object> uses;
    private Integer level;
    private Integer value;
    private Integer mod;
    private Boolean proficient;

    public AbilityDto() {}

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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public List<Map<String, String>> getDamage() {
        return damage;
    }

    public void setDamage(List<Map<String, String>> damage) {
        this.damage = damage;
    }

    public Map<String, Object> getRange() {
        return range;
    }

    public void setRange(Map<String, Object> range) {
        this.range = range;
    }

    public Map<String, Object> getUses() {
        return uses;
    }

    public void setUses(Map<String, Object> uses) {
        this.uses = uses;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    public Integer getValue() {
        return value;
    }

    public void setValue(Integer value) {
        this.value = value;
    }

    public Integer getMod() {
        return mod;
    }

    public void setMod(Integer mod) {
        this.mod = mod;
    }

    public Boolean getProficient() {
        return proficient;
    }

    public void setProficient(Boolean proficient) {
        this.proficient = proficient;
    }


    private String toString(List<AbilityDto> abilities) {
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
}

