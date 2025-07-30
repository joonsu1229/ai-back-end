package com.ai.hybridsearch.util;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;

@Converter(autoApply = false)
public class PGvectorConverter implements AttributeConverter<float[], String> {

    @Override
    public String convertToDatabaseColumn(float[] attribute) {
        if (attribute == null) return null;
        return Arrays.toString(attribute) // ex: "[1.0, 2.0, 3.0]" → PostgreSQL vector로 캐스팅
                   .replace("[", "'[")
                   .replace("]", "]'");
    }

    @Override
    public float[] convertToEntityAttribute(String dbData) {
        dbData = dbData.replace("[", "").replace("]", "").replace("'", ""); // 작은 따옴표 제거
        String[] tokens = dbData.split(",");
        float[] vector = new float[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            vector[i] = Float.parseFloat(tokens[i].trim());
        }
        return vector;
    }
}
