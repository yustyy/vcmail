package com.yusssss.vcmail.core.utilities.audio;

import be.tarsos.dsp.AudioDispatcher;
import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;
import be.tarsos.dsp.io.jvm.JVMAudioInputStream;
import be.tarsos.dsp.resample.RateTransposer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@Service
public class AudioConversionService {

    private static final Logger logger = LoggerFactory.getLogger(AudioConversionService.class);

    // OpenAI -> Asterisk (24kHz 16-bit PCM -> 8kHz 8-bit ULAW)
    public byte[] convertOpenAiToAsterisk(byte[] pcm24kHzData) {
        if (pcm24kHzData == null || pcm24kHzData.length == 0) return new byte[0];
        try {
            // 1️⃣ Resample 24kHz -> 8kHz
            byte[] pcm8kHzData = resample(pcm24kHzData, 24000, 8000);

            // 2️⃣ PCM 16-bit -> ULAW 8-bit
            AudioFormat pcmFormat = new AudioFormat(8000, 16, 1, true, false);
            AudioFormat ulawFormat = new AudioFormat(AudioFormat.Encoding.ULAW, 8000, 8, 1, 1, 8000, false);

            try (ByteArrayInputStream bais = new ByteArrayInputStream(pcm8kHzData);
                 AudioInputStream pcmStream = new AudioInputStream(bais, pcmFormat, pcm8kHzData.length / pcmFormat.getFrameSize());
                 AudioInputStream ulawStream = AudioSystem.getAudioInputStream(ulawFormat, pcmStream);
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = ulawStream.read(buffer)) != -1) {
                    baos.write(buffer, 0, bytesRead);
                }
                return baos.toByteArray();
            }

        } catch (Exception e) {
            logger.error("Error converting OpenAI audio to Asterisk format", e);
            return new byte[0];
        }
    }

    // Asterisk -> OpenAI (8kHz 8-bit ULAW -> 24kHz 16-bit PCM)
    public byte[] convertAsteriskToOpenAi(byte[] ulaw8kHzData) {
        if (ulaw8kHzData == null || ulaw8kHzData.length == 0) return new byte[0];
        try {
            AudioFormat ulawFormat = new AudioFormat(AudioFormat.Encoding.ULAW, 8000, 8, 1, 1, 8000, false);
            AudioFormat pcmFormat = new AudioFormat(8000, 16, 1, true, false);

            byte[] pcm8kHzData;
            try (ByteArrayInputStream bais = new ByteArrayInputStream(ulaw8kHzData);
                 AudioInputStream ulawStream = new AudioInputStream(bais, ulawFormat, ulaw8kHzData.length / ulawFormat.getFrameSize());
                 AudioInputStream pcmStream = AudioSystem.getAudioInputStream(pcmFormat, ulawStream);
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = pcmStream.read(buffer)) != -1) {
                    baos.write(buffer, 0, bytesRead);
                }
                pcm8kHzData = baos.toByteArray();
            }

            return resample(pcm8kHzData, 8000, 24000);

        } catch (Exception e) {
            logger.error("Error converting Asterisk audio to OpenAI format", e);
            return new byte[0];
        }
    }

    private byte[] resample(byte[] pcmData, int sourceRate, int targetRate) {
        try {
            double rateFactor = (double) targetRate / sourceRate;

            AudioFormat sourceFormat = new AudioFormat(sourceRate, 16, 1, true, false);
            JVMAudioInputStream jvmStream = new JVMAudioInputStream(
                    new AudioInputStream(new ByteArrayInputStream(pcmData), sourceFormat, pcmData.length / 2)
            );

            AudioDispatcher dispatcher = new AudioDispatcher(jvmStream, 1024, 0);
            RateTransposer rateTransposer = new RateTransposer(rateFactor);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            dispatcher.addAudioProcessor(rateTransposer);
            dispatcher.addAudioProcessor(new AudioProcessor() {
                @Override
                public boolean process(AudioEvent audioEvent) {
                    float[] buffer = audioEvent.getFloatBuffer();
                    byte[] byteBuffer = new byte[buffer.length * 2];
                    for (int i = 0; i < buffer.length; i++) {
                        short val = (short) (buffer[i] * 32767);
                        byteBuffer[i * 2] = (byte) (val & 0xFF);
                        byteBuffer[i * 2 + 1] = (byte) ((val >> 8) & 0xFF);
                    }
                    try { baos.write(byteBuffer); } catch (Exception ignored) {}
                    return true;
                }

                @Override
                public void processingFinished() {}
            });

            dispatcher.run();
            return baos.toByteArray();

        } catch (Exception e) {
            logger.error("Error during resampling from {}Hz to {}Hz", sourceRate, targetRate, e);
            return new byte[0];
        }
    }

    // Ses normalizasyonu
    public byte[] normalizeVolume(byte[] audioData, float targetLevel) {
        if (audioData == null || audioData.length < 2) return audioData;

        int sampleCount = audioData.length / 2;
        short[] samples = new short[sampleCount];
        ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);

        long sum = 0;
        for (short sample : samples) sum += (long) sample * sample;

        double rms = Math.sqrt((double) sum / sampleCount);
        if (rms == 0) return audioData;

        double targetRms = targetLevel * Short.MAX_VALUE;
        double gain = targetRms / rms;
        if (gain > 4.0) gain = 4.0;
        if (gain < 0.1) gain = 0.1;

        byte[] normalizedData = new byte[audioData.length];
        for (int i = 0; i < samples.length; i++) {
            int amplified = (int) (samples[i] * gain);
            amplified = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, amplified));
            normalizedData[i * 2] = (byte) (amplified & 0xFF);
            normalizedData[i * 2 + 1] = (byte) ((amplified >> 8) & 0xFF);
        }
        return normalizedData;
    }
}