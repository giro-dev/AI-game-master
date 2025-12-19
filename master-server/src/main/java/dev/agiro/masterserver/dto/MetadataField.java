package dev.agiro.masterserver.dto;

public enum MetadataField {
    GAME_SYSTEM("gameSystem"),
    FILE_NAME("fileName"),
    BOOK_SECTION("bookSection"),
    PAGE_NUMBER_START("pageNumberStart"),
    PAGE_NUMBER_END("pageNumberEnd"),
    LEVEL("level"),
    TYPE("type");


    private final String key;

    MetadataField(String key) {
        this.key = key;
    }


    public String key() {
        return key;
    }
}
