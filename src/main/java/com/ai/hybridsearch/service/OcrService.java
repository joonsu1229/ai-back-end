package com.ai.hybridsearch.service;

import java.io.IOException;

public interface OcrService {
    /**
     * Extracts text from the given image data using an OCR engine.
     *
     * @param imageData The byte array of the image file.
     * @return The extracted text.
     * @throws IOException if an I/O error occurs during the process.
     */
    String extractTextFromImage(byte[] imageData) throws IOException;
}
