package com.gyx.mp4musicclipcombine;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

//MP3-->MP31
public class MusicProcess {
	private static float normalizeVolume(int volume) {
		return volume / 100f * 1;
	}

	//     vol1  vol2  0-100  0静音  120
	public static void mixPcm(String pcm1Path, String pcm2Path, String toPath
			, int volume1, int volume2) throws IOException {
		float vol1 = normalizeVolume(volume1);
		float vol2 = normalizeVolume(volume2);
		//一次读取多一点 2k
		byte[] buffer1 = new byte[2048];
		byte[] buffer2 = new byte[2048];
		//        待输出数据
		byte[] buffer3 = new byte[2048];
		FileInputStream is1 = new FileInputStream(pcm1Path);
		FileInputStream is2 = new FileInputStream(pcm2Path);
		//输出PCM 的
		FileOutputStream fileOutputStream = new FileOutputStream(toPath);
		short temp2, temp1;//   两个short变量相加 会大于short   声音
		int temp;
		boolean end1 = false, end2 = false;
		while (!end1 || !end2) {
			if (!end1) {
				//
				end1 = (is1.read(buffer1) == -1);
				//            音乐的pcm数据  写入到 buffer3
				System.arraycopy(buffer1, 0, buffer3, 0, buffer1.length);
			}
			if (!end2) {
				end2 = (is2.read(buffer2) == -1);
				int voice = 0;//声音的值  跳过下一个声音的值    一个声音 2 个字节
				for (int i = 0; i < buffer2.length; i += 2) {
					//                    或运算
					temp1 = (short) ((buffer1[i] & 0xff) | (buffer1[i + 1] & 0xff) << 8);
					temp2 = (short) ((buffer2[i] & 0xff) | (buffer2[i + 1] & 0xff) << 8);
					temp = (int) (temp1 * vol1 + temp2 * vol2);//音乐和 视频声音 各占一半
					if (temp > 32767) {
						temp = 32767;
					} else if (temp < -32768) {
						temp = -32768;
					}
					buffer3[i] = (byte) (temp & 0xFF);
					buffer3[i + 1] = (byte) ((temp >>> 8) & 0xFF);
				}
				fileOutputStream.write(buffer3);
			}
		}
		is1.close();
		is2.close();
		fileOutputStream.close();
	}

	public void mixAudioTrack(Context context,
	                          final String videoInput,
	                          final String audioInput,
	                          final String output,
	                          final Integer startTimeUs, final Integer endTimeUs,
	                          int videoVolume,//视频声音大小
	                          int aacVolume//音频声音大小
	) throws Exception {
		final File videoPcmFile = new File(Environment.getExternalStorageDirectory(), "video_pcm.pcm");
		final File videoAACFile = new File(Environment.getExternalStorageDirectory(), "video_aac.aac");
		final File musicPcmFile = new File(Environment.getExternalStorageDirectory(), "music_pcm.pcm");
		final File musicAACFile = new File(Environment.getExternalStorageDirectory(), "music_aac.aac");
		//decodeToPCM(videoInput, videoPcmFile.getAbsolutePath(), startTimeUs, endTimeUs);
		decodeToAAC(videoInput, videoAACFile.getAbsolutePath());
		musicToPCM(videoAACFile.getAbsolutePath(), videoPcmFile.getAbsolutePath(), true, startTimeUs, endTimeUs);
		//decodeToPCM(audioInput, musicPcmFile.getAbsolutePath(), startTimeUs, endTimeUs);
		musicToPCM(audioInput, musicPcmFile.getAbsolutePath(), true, startTimeUs, endTimeUs);
		final File mixPcmFile = new File(Environment.getExternalStorageDirectory(), "mix.pcm");
		mixPcm(
				videoPcmFile.getAbsolutePath(),
				musicPcmFile.getAbsolutePath(),
				mixPcmFile.getAbsolutePath(),
				videoVolume,
				aacVolume);
		new PcmToWavUtil(
				44100,
				AudioFormat.CHANNEL_IN_STEREO,
				2,
				AudioFormat.ENCODING_PCM_16BIT
		).pcmToWav(
				mixPcmFile.getAbsolutePath(),
				output
		);
	}

	//    MP3 截取并且输出  pcm
	@SuppressLint("WrongConstant")
	public void decodeToPCM(String musicPath, String outPath, int startTime, int endTime) throws Exception {
		if (endTime < startTime) {
			return;
		}
		//    MP3  （zip  rar    ） ----> aac   封装个事 1   编码格式
		//        jie  MediaExtractor = 360 解压 工具
		MediaExtractor mediaExtractor = new MediaExtractor();
		mediaExtractor.setDataSource(musicPath);
		int audioTrack = selectTrack(mediaExtractor);
		mediaExtractor.selectTrack(audioTrack);
		// 视频 和音频
		mediaExtractor.seekTo(startTime, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
		// 轨道信息  都记录 编码器
		MediaFormat oriAudioFormat = mediaExtractor.getTrackFormat(audioTrack);
		int maxBufferSize = 100 * 1000;
		if (oriAudioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
			maxBufferSize = oriAudioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
		} else {
			maxBufferSize = 100 * 1000;
		}
		ByteBuffer buffer = ByteBuffer.allocateDirect(maxBufferSize);
		//        h264   H265  音频
		MediaCodec mediaCodec = MediaCodec.createDecoderByType(oriAudioFormat.getString((MediaFormat.KEY_MIME)));
		//        设置解码器信息    直接从 音频文件
		mediaCodec.configure(oriAudioFormat, null, null, 0);
		File pcmFile = new File(outPath);
		FileChannel writeChannel = new FileOutputStream(pcmFile).getChannel();
		mediaCodec.start();
		MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
		int outputBufferIndex = -1;
		while (true) {
			int decodeInputIndex = mediaCodec.dequeueInputBuffer(100000);
			if (decodeInputIndex >= 0) {
				long sampleTimeUs = mediaExtractor.getSampleTime();
				if (sampleTimeUs == -1) {
					break;
				} else if (sampleTimeUs < startTime) {
					//                    丢掉 不用了
					mediaExtractor.advance();
					continue;
				} else if (sampleTimeUs > endTime) {
					break;
				}
				//                获取到压缩数据
				info.size = mediaExtractor.readSampleData(buffer, 0);
				info.presentationTimeUs = sampleTimeUs;
				info.flags = mediaExtractor.getSampleFlags();
				//                下面放数据  到dsp解码
				byte[] content = new byte[buffer.remaining()];
				buffer.get(content);
				//                输出文件  方便查看
				//                FileUtils.writeContent(content);
				//                解码
				ByteBuffer inputBuffer = mediaCodec.getInputBuffer(decodeInputIndex);
				inputBuffer.put(content);
				mediaCodec.queueInputBuffer(decodeInputIndex, 0, info.size, info.presentationTimeUs, info.flags);
				//                释放上一帧的压缩数据
				mediaExtractor.advance();
			}
			outputBufferIndex = mediaCodec.dequeueOutputBuffer(info, 100_000);
			while (outputBufferIndex >= 0) {
				ByteBuffer decodeOutputBuffer = mediaCodec.getOutputBuffer(outputBufferIndex);
				writeChannel.write(decodeOutputBuffer);//MP3  1   pcm2
				mediaCodec.releaseOutputBuffer(outputBufferIndex, false);
				outputBufferIndex = mediaCodec.dequeueOutputBuffer(info, 100_000);
			}
		}
		writeChannel.close();
		mediaExtractor.release();
		mediaCodec.stop();
		mediaCodec.release();
		//        转换MP3    pcm数据转换成mp3封装格式
		//
		//        File wavFile = new File(Environment.getExternalStorageDirectory(),"output.mp3" );
		//        new PcmToWavUtil(44100,  AudioFormat.CHANNEL_IN_STEREO,
		//                2, AudioFormat.ENCODING_PCM_16BIT).pcmToWav(pcmFile.getAbsolutePath()
		//                , wavFile.getAbsolutePath());
		Log.i("David", "mixAudioTrack: 转换完毕");
	}

	/**
	 * mp4 分离出 aac
	 *
	 * @param musicPath
	 * @param outPath
	 */
	@SuppressLint("WrongConstant")
	public void decodeToAAC(String musicPath, String outPath) {
		MediaExtractor extractor = new MediaExtractor();
		try {
			extractor.setDataSource(musicPath);
		} catch (IOException e) {
			e.printStackTrace();
		}
		int audioTrack = 0;
		for (int i = 0; i < extractor.getTrackCount(); i++) {
			MediaFormat trackFormat = extractor.getTrackFormat(i);
			String mime = trackFormat.getString(MediaFormat.KEY_MIME);
			if (mime.startsWith("audio/")) {
				audioTrack = i;
				break;
			}
		}
		//选择 音频通道
		extractor.selectTrack(audioTrack);
		try {
			MediaMuxer mediaMuxer = new MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
			MediaFormat trackFormat = extractor.getTrackFormat(audioTrack);
			int writeAudioIndex = mediaMuxer.addTrack(trackFormat);
			mediaMuxer.start();
			ByteBuffer byteBuffer = ByteBuffer.allocate(trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
			MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
			extractor.readSampleData(byteBuffer, 0);
			if (extractor.getSampleFlags() == MediaExtractor.SAMPLE_FLAG_SYNC) {
				extractor.advance();
			}
			while (true) {
				int readSampleSize = extractor.readSampleData(byteBuffer, 0);
				Log.e("hero", "---读取音频数据，当前读取到的大小-----：：：" + readSampleSize);
				if (readSampleSize < 0) {
					break;
				}
				bufferInfo.size = readSampleSize;
				bufferInfo.flags = extractor.getSampleFlags();
				bufferInfo.offset = 0;
				bufferInfo.presentationTimeUs = extractor.getSampleTime();
				Log.e("hero", "----写入音频数据---当前的时间戳：：：" + extractor.getSampleTime());
				mediaMuxer.writeSampleData(writeAudioIndex, byteBuffer, bufferInfo);
				extractor.advance();//移动到下一帧
			}
			mediaMuxer.release();
			extractor.release();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * 带有剪辑功能
	 *
	 * @param musicPath aac 路径
	 * @param outPath   输出pcm路径
	 * @param needClip  是否需要剪辑
	 * @param startTime 开始时间 毫秒
	 * @param endTime   结束时间 毫秒
	 */
	public void musicToPCM(String musicPath, String outPath, boolean needClip, int startTime, int endTime) {
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		MediaExtractor extractor = new MediaExtractor();
		try {
			extractor.setDataSource(musicPath);
		} catch (IOException e) {
			e.printStackTrace();
		}
		int audioTrack = -1;
		for (int i = 0; i < extractor.getTrackCount(); i++) {
			MediaFormat trackFormat = extractor.getTrackFormat(i);
			String mime = trackFormat.getString(MediaFormat.KEY_MIME);
			if (mime.startsWith("audio/")) {
				audioTrack = i;
				break;
			}
		}
		extractor.selectTrack(audioTrack);
		MediaFormat trackFormat = extractor.getTrackFormat(audioTrack);
		//初始化音频的解码器
		MediaCodec audioCodec = null;
		try {
			audioCodec = MediaCodec.createDecoderByType(trackFormat.getString(MediaFormat.KEY_MIME));
			// 采样率
			int KEY_SAMPLE_RATE = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
			// 通道数
			int KEY_CHANNEL_COUNT = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
			// 采样 位数
			int KEY_PCM_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
			if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && trackFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
				KEY_PCM_ENCODING = trackFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
			}
			int bitNumber;
			switch (KEY_PCM_ENCODING) {
				case AudioFormat.ENCODING_PCM_FLOAT:
					bitNumber = 32;
					break;
				case AudioFormat.ENCODING_PCM_8BIT:
					bitNumber = 8;
					break;
				case AudioFormat.ENCODING_PCM_16BIT:
				default:
					bitNumber = 16;
					break;
			}
			audioCodec.configure(trackFormat, null, null, 0);
			audioCodec.start();
			MediaCodec.BufferInfo decodeBufferInfo = new MediaCodec.BufferInfo();
			MediaCodec.BufferInfo inputInfo = new MediaCodec.BufferInfo();
			//FileOutputStream fos = new FileOutputStream(videoPcmFile.getAbsolutePath());
			//boolean inputDone = false;//整体输入结束标志
			boolean codeOver = false;
			//遍历所以的编码器 然后将数据传入之后 再去输出端取数据
			while (!codeOver) {
				int inputIndex = audioCodec.dequeueInputBuffer(0);
				if (inputIndex > 0) {
					ByteBuffer inputBuffer = audioCodec.getInputBuffer(inputIndex);
					inputBuffer.clear();//清空之前传入inputBuffer内的数据
					int sampleSize = extractor.readSampleData(inputBuffer, 0);//MediaExtractor读取数据到inputBuffer中
					if (sampleSize < 0) {
						audioCodec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
					} else {
						inputInfo.offset = 0;
						inputInfo.size = sampleSize;
						inputInfo.flags = MediaCodec.BUFFER_FLAG_SYNC_FRAME;
						inputInfo.presentationTimeUs = extractor.getSampleTime();
						Log.e("hero", "往解码器写入数据---当前帧的时间戳----" + inputInfo.presentationTimeUs);
						audioCodec.queueInputBuffer(inputIndex, inputInfo.offset, sampleSize, inputInfo.presentationTimeUs, 0);//通知MediaDecode解码刚刚传入的数据
						extractor.advance();//MediaExtractor移动到下一取样处
					}
				}
				int outputIndex = audioCodec.dequeueOutputBuffer(decodeBufferInfo, 0);
				if (outputIndex > 0) {
					ByteBuffer outputBuffer = audioCodec.getOutputBuffer(outputIndex);
					byte[] chunkPCM = new byte[decodeBufferInfo.size];
					outputBuffer.get(chunkPCM);
					outputBuffer.clear();
					//fos.write(chunkPCM);//数据写入文件中
					byteArrayOutputStream.write(chunkPCM);
					//fos.flush();
					Log.e("hero", "---释放输出流缓冲区----:::" + outputIndex);
					audioCodec.releaseOutputBuffer(outputIndex, false);
					if ((decodeBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
						/**
						 * 解码结束，释放分离器和解码器
						 * */
						extractor.release();
						audioCodec.stop();
						audioCodec.release();
						codeOver = true;
					}
				}
			}
			//fos.close();//输出流释放
			if (needClip) {
				byte[] bytes = byteArrayOutputStream.toByteArray();//读取整个音频的数据
				//获取数据开始数据索引
				int startPosition = getPositionFromWave(startTime, KEY_SAMPLE_RATE, KEY_CHANNEL_COUNT, bitNumber);
				//获取数据结束数据索引
				int endPosition = endTime == -1 ? bytes.length - 1 : getPositionFromWave(endTime, KEY_SAMPLE_RATE, KEY_CHANNEL_COUNT, bitNumber);
				if (endPosition > bytes.length - 1) {
					endPosition = bytes.length - 1;
				}
				if (startPosition == endPosition - 1 || startPosition >= endPosition) {
					return;
				}
				byte[] cutBytes;//= Arrays.copyOfRange(bytes,startPosition,endPosition+1);
				if (KEY_CHANNEL_COUNT == 1) {//如果是单通道需要转为双通道
					cutBytes = byteMerger(Arrays.copyOfRange(bytes, startPosition, endPosition + 1));
				} else {
					cutBytes = Arrays.copyOfRange(bytes, startPosition, endPosition + 1);
				}
				FileOutputStream cutFileOutputStream = new FileOutputStream(outPath);
				cutFileOutputStream.write(cutBytes);
				cutFileOutputStream.close();
				byteArrayOutputStream.close();
			} else {
				byte[] bytes = byteArrayOutputStream.toByteArray();//读取整个音频的数据
				FileOutputStream cutFileOutputStream = new FileOutputStream(outPath);
				cutFileOutputStream.write(bytes);
				cutFileOutputStream.close();
				byteArrayOutputStream.close();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 获取wave文件某个时间对应的数据位置
	 *
	 * @param time       时间  毫秒
	 * @param sampleRate 采样率
	 * @param channels   声道数
	 * @param bitNum     采样位数
	 * @return
	 */
	private static int getPositionFromWave(long time, int sampleRate, int channels, int bitNum) {
		int byteNum = bitNum / 8;
		//
		int position = (int) (time * sampleRate * channels * byteNum / 1000);
		//这里要特别注意，要取整（byteNum * channels）的倍数,当time 是float的时候
		position = position / (byteNum * channels) * (byteNum * channels);
		return position;
	}

	/**
	 * 单声道 转 双声道
	 *
	 * @param byte_1
	 * @return
	 */
	private byte[] byteMerger(byte[] byte_1) {
		//根据单声道的长度，两倍创建新的双声道 byte数组
		byte[] byte_2 = new byte[byte_1.length * 2];
		for (int i = 0; i < byte_1.length; i++) {
			//偶数
			if (i % 2 == 0) {
				byte_2[2 * i] = byte_1[i];
				byte_2[2 * i + 1] = byte_1[i + 1];
			} else {
				byte_2[2 * i] = byte_1[i - 1];
				byte_2[2 * i + 1] = byte_1[i];
			}
		}
		return byte_2;
	}

	private int selectTrack(MediaExtractor mediaExtractor) {
		//获取每条轨道
		int numTracks = mediaExtractor.getTrackCount();
		for (int i = 0; i < numTracks; i++) {
			//            数据      MediaFormat
			MediaFormat format = mediaExtractor.getTrackFormat(i);
			String mime = format.getString(MediaFormat.KEY_MIME);
			if (mime.startsWith("audio/")) {
				return i;
			}
		}
		return -1;
	}
}
