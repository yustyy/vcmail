package com.yusssss.vcmail.core.utilities.audio;

import be.tarsos.dsp.io.jvm.JVMAudioInputStream;
import be.tarsos.dsp.resample.Resampler;
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
import java.util.Arrays;

@Service
public class AudioConversionService {

    private static final Logger logger = LoggerFactory.getLogger(AudioConversionService.class);

    // OpenAI -> Asterisk (24kHz 16-bit PCM -> 8kHz 8-bit ULAW)
    public byte[] convertOpenAiToAsterisk(byte[] pcm24kHzData) {
        if (pcm24kHzData == null || pcm24kHzData.length == 0) return new byte[0];
        try {
            byte[] pcm8kHzData = resamplePcm(pcm24kHzData, 24000, 8000);
            return pcmToUlaw(pcm8kHzData);
        } catch (Exception e) {
            logger.error("Error converting OpenAI audio to Asterisk format", e);
            return new byte[0];
        }
    }

    // Asterisk -> OpenAI (8kHz 8-bit ULAW -> 24kHz 16-bit PCM)
    public byte[] convertAsteriskToOpenAi(byte[] ulaw8kHzData) {
        if (ulaw8kHzData == null || ulaw8kHzData.length == 0) return new byte[0];
        try {
            byte[] pcm8kHzData = ulawToPcm(ulaw8kHzData);
            return resamplePcm(pcm8kHzData, 8000, 24000);
        } catch (Exception e) {
            logger.error("Error converting Asterisk audio to OpenAI format", e);
            return new byte[0];
        }
    }

    private byte[] pcmToUlaw(byte[] pcmData) {
        return convertWithJavaSound(pcmData,
                new AudioFormat(8000, 16, 1, true, false),
                new AudioFormat(AudioFormat.Encoding.ULAW, 8000, 8, 1, 1, 8000, false));
    }

    private byte[] ulawToPcm(byte[] ulawData) {
        return convertWithJavaSound(ulawData,
                new AudioFormat(AudioFormat.Encoding.ULAW, 8000, 8, 1, 1, 8000, false),
                new AudioFormat(8000, 16, 1, true, false));
    }

    private byte[] convertWithJavaSound(byte[] sourceBytes, AudioFormat sourceFormat, AudioFormat targetFormat) {
        try (
                ByteArrayInputStream bais = new ByteArrayInputStream(sourceBytes);
                AudioInputStream sourceStream = new AudioInputStream(bais, sourceFormat, sourceBytes.length / sourceFormat.getFrameSize());
                AudioInputStream targetStream = AudioSystem.getAudioInputStream(targetFormat, sourceStream);
                ByteArrayOutputStream baos = new ByteArrayOutputStream()
        ) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = targetStream.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            logger.error("JavaSound conversion failed from {} to {}", sourceFormat, targetFormat, e);
            return new byte[0];
        }
    }

    private byte[] resamplePcm(byte[] pcmData, int sourceRate, int targetRate) {
        float[] samples = bytesToFloats(pcmData);
        Resampler resampler = new Resampler(false, 0.9, 4.0);

        float[] resampled = new float[(int) (samples.length * ((double)targetRate/sourceRate))];
        resampler.process((double)targetRate/sourceRate, samples, 0, samples.length, false, resampled, 0, resampled.length);

        return floatsToBytes(resampled);
    }

    private float[] bytesToFloats(byte[] pcmBytes) {
        short[] shortSamples = new short[pcmBytes.length / 2];
        ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortSamples);

        float[] floatSamples = new float[shortSamples.length];
        for(int i = 0; i < shortSamples.length; i++){
            floatSamples[i] = shortSamples[i] / 32768.0f;
        }
        return floatSamples;
    }

    private byte[] floatsToBytes(float[] floatSamples) {
        ByteBuffer buffer = ByteBuffer.allocate(floatSamples.length * 2);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for(float sample : floatSamples){
            buffer.putShort((short) (sample * 32767.0));
        }
        return buffer.array();
    }


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