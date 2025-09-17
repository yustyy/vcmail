package com.yusssss.vcmail.core.utilities.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@Service
public class AudioConversionService {

    private static final Logger logger = LoggerFactory.getLogger(AudioConversionService.class);

    // -------------------------------
    // OpenAI PCM 24kHz -> Asterisk μ-law 8kHz
    // -------------------------------
    public byte[] convertOpenAiToAsterisk(byte[] pcm24kHzData) {
        if (pcm24kHzData == null || pcm24kHzData.length == 0) return new byte[0];

        try {
            // 1️⃣ PCM24kHz -> PCM8kHz
            short[] pcm16 = bytesToShorts(pcm24kHzData);
            short[] resampled = resampleLinear(pcm16, 24000, 8000);

            // 2️⃣ PCM16 -> μ-law
            return pcm16ToUlaw(resampled);
        } catch (Exception e) {
            logger.error("Error converting OpenAI audio to Asterisk format", e);
            return new byte[0];
        }
    }

    // -------------------------------
    // Asterisk μ-law 8kHz -> OpenAI PCM 24kHz
    // -------------------------------
    public byte[] convertAsteriskToOpenAi(byte[] ulaw8kHzData) {
        if (ulaw8kHzData == null || ulaw8kHzData.length == 0) return new byte[0];

        try {
            // 1️⃣ μ-law -> PCM16
            short[] pcm8kHz = ulawToPcm16(ulaw8kHzData);

            // 2️⃣ PCM8kHz -> PCM24kHz
            short[] pcm24kHz = resampleLinear(pcm8kHz, 8000, 24000);

            return shortsToBytes(pcm24kHz);
        } catch (Exception e) {
            logger.error("Error converting Asterisk audio to OpenAI format", e);
            return new byte[0];
        }
    }

    // -------------------------------
    // Yardımcı Metotlar
    // -------------------------------

    private short[] bytesToShorts(byte[] bytes) {
        short[] shorts = new short[bytes.length / 2];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts);
        return shorts;
    }

    private byte[] shortsToBytes(short[] shorts) {
        ByteBuffer buffer = ByteBuffer.allocate(shorts.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asShortBuffer().put(shorts);
        return buffer.array();
    }

    // -------------------------------
    // PCM16 <-> μ-law
    // -------------------------------
    private byte[] pcm16ToUlaw(short[] pcm) {
        byte[] ulaw = new byte[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            ulaw[i] = linearToUlaw(pcm[i]);
        }
        return ulaw;
    }

    private short[] ulawToPcm16(byte[] ulaw) {
        short[] pcm = new short[ulaw.length];
        for (int i = 0; i < ulaw.length; i++) {
            pcm[i] = ulawToLinear(ulaw[i]);
        }
        return pcm;
    }

    // μ-law algoritması (A-law yerine)
    private static byte linearToUlaw(short sample) {
        final int BIAS = 0x84;
        final int CLIP = 32635;
        int sign = (sample >> 8) & 0x80;
        if (sign != 0) sample = (short) -sample;
        if (sample > CLIP) sample = CLIP;
        sample += BIAS;

        int exponent = 7;
        for (int expMask = 0x4000; (sample & expMask) == 0 && exponent > 0; exponent--, expMask >>= 1);

        int mantissa = (sample >> (exponent + 3)) & 0x0F;
        return (byte) ~(sign | (exponent << 4) | mantissa);
    }

    private static short ulawToLinear(byte ulawByte) {
        final int BIAS = 0x84;
        ulawByte = (byte) ~ulawByte;
        int sign = ulawByte & 0x80;
        int exponent = (ulawByte >> 4) & 0x07;
        int mantissa = ulawByte & 0x0F;
        int sample = ((mantissa << 3) + 0x84) << exponent;
        return (short) (sign != 0 ? -sample : sample);
    }

    // -------------------------------
    // Basit lineer yeniden örnekleme
    // -------------------------------
    private short[] resampleLinear(short[] input, int srcRate, int targetRate) {
        int outputLength = (int) ((long) input.length * targetRate / srcRate);
        short[] output = new short[outputLength];

        for (int i = 0; i < outputLength; i++) {
            float srcIndex = ((float) i * srcRate) / targetRate;
            int index0 = (int) Math.floor(srcIndex);
            int index1 = Math.min(index0 + 1, input.length - 1);
            float frac = srcIndex - index0;
            output[i] = (short) ((1 - frac) * input[index0] + frac * input[index1]);
        }

        return output;
    }

    // -------------------------------
    // Ses normalizasyonu (PCM16)
    // -------------------------------
    public byte[] normalizeVolume(byte[] audioData, float targetLevel) {
        if (audioData == null || audioData.length < 2) return audioData;

        short[] samples = bytesToShorts(audioData);
        long sum = 0;
        for (short s : samples) sum += (long) s * s;

        double rms = Math.sqrt((double) sum / samples.length);
        if (rms == 0) return audioData;

        double targetRms = targetLevel * Short.MAX_VALUE;
        double gain = targetRms / rms;
        if (gain > 4.0) gain = 4.0;
        if (gain < 0.1) gain = 0.1;

        for (int i = 0; i < samples.length; i++) {
            int amplified = (int) (samples[i] * gain);
            amplified = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, amplified));
            samples[i] = (short) amplified;
        }

        return shortsToBytes(samples);
    }
}