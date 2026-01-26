package com.ai.hybridsearch.service.impl;

import com.ai.hybridsearch.service.OcrService;
import com.google.cloud.vision.v1.*;
import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
@Slf4j
public class GoogleOcrServiceImpl implements OcrService {

    @Override
    public String extractTextFromImage(byte[] imageData) throws IOException {
        log.info("Starting OCR process with Google Cloud Vision...");
        // try-with-resources to automatically close the client
        try (ImageAnnotatorClient vision = ImageAnnotatorClient.create()) {
            ByteString imgBytes = ByteString.copyFrom(imageData);

            List<AnnotateImageRequest> requests = new ArrayList<>();
            Image img = Image.newBuilder().setContent(imgBytes).build();
            Feature feat = Feature.newBuilder().setType(Feature.Type.TEXT_DETECTION).build();
            AnnotateImageRequest request = AnnotateImageRequest.newBuilder()
                    .addFeatures(feat)
                    .setImage(img)
                    .build();
            requests.add(request);

            // Perform the OCR request
            BatchAnnotateImagesResponse response = vision.batchAnnotateImages(requests);
            List<AnnotateImageResponse> responses = response.getResponsesList();

            if (responses.isEmpty()) {
                log.warn("Received empty response from Google Cloud Vision API.");
                return "";
            }

            AnnotateImageResponse res = responses.get(0);
            if (res.hasError()) {
                log.error("Google Cloud Vision API Error: {}", res.getError().getMessage());
                throw new IOException("Failed to extract text from image due to Vision API error: " + res.getError().getMessage());
            }

            // The first TextAnnotation contains the full, combined text from the image.
            if (res.getTextAnnotationsList().isEmpty()) {
                log.warn("No text found in the image by Google Cloud Vision API.");
                return "";
            }

            String extractedText = res.getTextAnnotationsList().get(0).getDescription();
            log.info("Successfully extracted text from image. Text length: {}", extractedText.length());
            return extractedText;
        } catch (IOException e) {
            // This can happen if the GOOGLE_APPLICATION_CREDENTIALS are not set correctly
            log.error("Failed to create or connect to Google Cloud Vision client. Please ensure GOOGLE_APPLICATION_CREDENTIALS environment variable is set correctly.", e);
            throw new IOException("Could not connect to Google Vision API. Check credentials and network.", e);
        }
    }

}
