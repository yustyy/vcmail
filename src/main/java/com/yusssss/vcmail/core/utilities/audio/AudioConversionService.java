package com.yusssss.vcmail.core.utilities.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

@Service
public class AudioConversionService {

    private static final Logger logger = LoggerFactory.getLogger(AudioConversionService.class);

    public byte[] convertOpenAiToAsterisk(byte[] pcm16Data) {
        if (pcm16Data == null || pcm16Data.length == 0) {
            return new byte[0];
        }

        try {
            // OpenAI format: PCM16, 24kHz, mono
            AudioFormat sourceFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    24000.0f,  // 24kHz sample rate
                    16,        // 16 bits per sample
                    1,         // mono
                    2,         // 2 bytes per frame (16-bit)
                    24000.0f,  // frame rate
                    false      // little-endian
            );

            // Asterisk slin16 format: PCM16, 8kHz, mono
            AudioFormat targetFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    8000.0f,   // 8kHz sample rate
                    16,        // 16 bits per sample
                    1,         // mono
                    2,         // 2 bytes per frame
                    8000.0f,   // frame rate
                    false      // little-endian
            );

            return resampleAudio(pcm16Data, sourceFormat, targetFormat);

        } catch (Exception e) {
            logger.error("Error converting OpenAI audio to Asterisk format", e);
            return fallbackDownsample(pcm16Data);
        }
    }


    public byte[] convertAsteriskToOpenAi(byte[] asteriskData) {
        if (asteriskData == null || asteriskData.length == 0) {
            return new byte[0];
        }

        try {
            AudioFormat sourceFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    8000.0f,   // 8kHz
                    16,        // 16 bits
                    1,         // mono
                    2,         // 2 bytes per frame
                    8000.0f,   // frame rate
                    false      // little-endian
            );

            AudioFormat targetFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    24000.0f,  // 24kHz
                    16,        // 16 bits
                    1,         // mono
                    2,         // 2 bytes per frame
                    24000.0f,  // frame rate
                    false      // little-endian
            );

            return resampleAudio(asteriskData, sourceFormat, targetFormat);

        } catch (Exception e) {
            logger.error("Error converting Asterisk audio to OpenAI format", e);
            return fallbackUpsample(asteriskData);
        }
    }

    private byte[] resampleAudio(byte[] inputData, AudioFormat sourceFormat, AudioFormat targetFormat) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(inputData);
            AudioInputStream sourceStream = new AudioInputStream(bais, sourceFormat, inputData.length / sourceFormat.getFrameSize());

            if (AudioSystem.isConversionSupported(targetFormat, sourceFormat)) {
                AudioInputStream targetStream = AudioSystem.getAudioInputStream(targetFormat, sourceStream);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();

                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = targetStream.read(buffer)) != -1) {
                    baos.write(buffer, 0, bytesRead);
                }

                targetStream.close();
                sourceStream.close();

                return baos.toByteArray();
            } else {
                logger.warn("Direct audio conversion not supported, using fallback method");
                sourceStream.close();

                if (targetFormat.getSampleRate() > sourceFormat.getSampleRate()) {
                    return fallbackUpsample(inputData);
                } else {
                    return fallbackDownsample(inputData);
                }
            }
        } catch (Exception e) {
            logger.error("Error in audio resampling", e);
            return inputData;
        }
    }


    private byte[] fallbackDownsample(byte[] input) {
        // Simple 3:1 decimation (24kHz to 8kHz)
        int ratio = 3;
        byte[] output = new byte[input.length / ratio];

        for (int i = 0, j = 0; i < input.length - 1 && j < output.length - 1; i += ratio * 2, j += 2) {
            // Copy every 3rd sample (16-bit = 2 bytes)
            if (i + 1 < input.length) {
                output[j] = input[i];
                output[j + 1] = input[i + 1];
            }
        }

        return output;
    }


    private byte[] fallbackUpsample(byte[] input) {
        // Simple 1:3 interpolation (8kHz to 24kHz)
        int ratio = 3;
        byte[] output = new byte[input.length * ratio];

        for (int i = 0, j = 0; i < input.length - 1 && j < output.length - 5; i += 2, j += ratio * 2) {
            // Repeat each sample 3 times
            for (int k = 0; k < ratio && j + k * 2 + 1 < output.length; k++) {
                output[j + k * 2] = input[i];
                output[j + k * 2 + 1] = input[i + 1];
            }
        }

        return output;
    }


    public boolean isValidAudioData(byte[] audioData, int expectedSampleRate) {
        if (audioData == null || audioData.length == 0) {
            return false;
        }

        // Minimum expected length check (at least 20ms of audio)
        int minimumBytes = (expectedSampleRate * 2 * 20) / 1000; // 2 bytes per sample, 20ms
        return audioData.length >= minimumBytes;
    }


    public byte[] normalizeVolume(byte[] audioData, float targetLevel) {
        if (audioData == null || audioData.length < 2) {
            return audioData;
        }

        long sum = 0;
        int sampleCount = audioData.length / 2;

        for (int i = 0; i < audioData.length - 1; i += 2) {
            short sample = (short) ((audioData[i + 1] << 8) | (audioData[i] & 0xFF));
            sum += sample * sample;
        }

        double rms = Math.sqrt((double) sum / sampleCount);

        if (rms == 0) {
            return audioData;
        }

        // Calculate gain factor
        double targetRms = targetLevel * Short.MAX_VALUE;
        double gain = targetRms / rms;

        // Apply gain (with clipping prevention)
        byte[] normalizedData = new byte[audioData.length];
        for (int i = 0; i < audioData.length - 1; i += 2) {
            short sample = (short) ((audioData[i + 1] << 8) | (audioData[i] & 0xFF));
            int amplified = (int) (sample * gain);

            // Clip to prevent distortion
            amplified = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, amplified));

            normalizedData[i] = (byte) (amplified & 0xFF);
            normalizedData[i + 1] = (byte) ((amplified >> 8) & 0xFF);
        }

        return normalizedData;
    }
}