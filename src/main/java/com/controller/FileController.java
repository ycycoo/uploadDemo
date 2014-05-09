package com.controller;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.sf.json.JSONObject;
import net.sf.json.util.JSONBuilder;

import org.codehaus.jackson.annotate.JsonBackReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import com.model.FileChunk;
import com.model.FileMeta;

@Controller
@RequestMapping("/controller")
public class FileController {

	final Logger logger = LoggerFactory.getLogger(FileController.class);
	LinkedList<FileMeta> files = new LinkedList<FileMeta>();
	FileMeta fileMeta = null;
	
	ConcurrentHashMap<String, ArrayList<FileChunk>> listMap = new ConcurrentHashMap<String, ArrayList<FileChunk>>();
	
	String tempPath = "D:/temp/";
	/***************************************************
	 * URL: /rest/controller/upload upload(): receives files
	 * 
	 * @param request
	 *            : MultipartHttpServletRequest auto passed
	 * @param response
	 *            : HttpServletResponse auto passed
	 * @return LinkedList<FileMeta> as json format
	 * @throws Exception
	 ****************************************************/
	@RequestMapping(value = "/upload", method = RequestMethod.POST)
	public @ResponseBody
	FileChunk upload(
			MultipartHttpServletRequest request,
			HttpServletResponse response,
			@RequestParam(value = "total", required = false, defaultValue = "-1") int chunks,
			@RequestParam(value = "userId", required = false, defaultValue = "-1") final String userId,
			@RequestParam(value = "curIndex", required = false, defaultValue = "-1") int chunk)
			throws Exception {
		request.setCharacterEncoding("UTF-8");
		response.setHeader("Access-Control-Allow-Origin", "*");
		MultipartFile mpf = request.getFile("files[]");
		String disposition = request.getHeader("Content-Disposition");
		String content_range = request.getHeader("Content-Range");
		String ContentLength = request.getHeader("Content-Length");
		String pos[] = null;
		long startPos = 0;
		long endPos = 0;
		long totalSize = 0;
		logger.info("userId: " + userId);

		ArrayList<FileChunk> filechunks = null;
		
		//对分块上传文件的处理
		if (null != content_range) {
			
			if(!listMap.containsKey(userId)){
				filechunks = new ArrayList<FileChunk>();
			}
			else{
				filechunks = listMap.get(userId);
			}
				pos = content_range.replaceAll("bytes ", "").split("/")[0]
						.split("-");
				startPos = Long.valueOf(pos[0]);
				endPos = Long.valueOf(pos[1]);
				totalSize = Long.valueOf(content_range.replaceAll("bytes ", "")
						.split("/")[1]);
				final String fileName = URLDecoder.decode(getFilename(disposition), "UTF-8");
//				
//				logger.info("disposition " + disposition + "content_range "
//						+ content_range + "fileName: " + fileName + ",ContentLength: "
//						+ ContentLength);
				boolean finished = addChunkFile(mpf, startPos, endPos, totalSize,
						fileName, tempPath);
				FileChunk fileChunk = new FileChunk();
				fileChunk.setName(fileName + startPos);
				fileChunk.setSize(endPos - startPos);
				fileChunk.setType(mpf.getContentType());
				fileChunk.setOriginName(fileName);
				filechunks.add(fileChunk);
			    
			    listMap.putIfAbsent(userId, filechunks);
			    
				if (finished) {
					logger.info("upload chunk finished,begin merge: " + userId);
					Thread tmpThread = new Thread(new Runnable() {
						@Override
						public void run() {
							try {
								mergeFile(listMap.get(userId), fileName, userId);
//								mergeFileWithChannel(filechunks, fileName);
								listMap.get(userId).clear();
							} catch (Exception e) {
								logger.error("mergeFile error", e);
							}
						}
					});
					tmpThread.setName("merge-uid"+ userId);
					tmpThread.start();
				}
				return fileChunk;
			
		}
		//对非分块文件处理
		else {
			String uniqueFileName = mpf.getOriginalFilename();
			FileCopyUtils.copy(mpf.getBytes(), new FileOutputStream(tempPath+ uniqueFileName));
			FileChunk fileChunk = new FileChunk();
			fileChunk.setName(uniqueFileName);
			fileChunk.setSize(Long.valueOf(ContentLength));
			fileChunk.setType(mpf.getContentType());
			fileChunk.setOriginName(uniqueFileName);
			return fileChunk;
		}

	}

	/**
	 * 合并文件
	 * @param filechunks
	 * @param originName 
	 * @param uid TODO
	 * @throws IOException 
	 */
	protected void mergeFile(List<FileChunk> filechunks, String originName, String uid) throws IOException {
		FileOutputStream outStream = new FileOutputStream(tempPath+originName);
		long startTime = System.currentTimeMillis();
		for (FileChunk chunk : filechunks) {
//			logger.info("start merge chunk: " + chunk.getName());
			try {
				FileInputStream in = new FileInputStream(new File(tempPath + chunk.getName()));
				int len = -1;
				byte[] buff = new byte[1024];
				while ((len = in.read(buff)) != -1) {
					outStream.write(buff, 0, len);
				}
				in.close();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		logger.info("close out stream,merge chunk file spend: " + (System.currentTimeMillis()-startTime)/1000 + " s");
		outStream.flush();
		outStream.close();
	}
	
	/**
	 * NIO 合并文件
	 * @param filechunks
	 * @param originName
	 * @throws IOException
	 */
	protected void mergeFileWithChannel(List<FileChunk> filechunks,
			String originName) throws IOException {
		long startTime = System.currentTimeMillis();

		FileOutputStream fout = new FileOutputStream(tempPath + originName);

		for (FileChunk chunk : filechunks) {
			logger.info("start merge chunk: " + chunk.getName());
			try {

				FileInputStream fin = new FileInputStream(new File(tempPath
						+ chunk.getName()));
				FileChannel fcin = fin.getChannel();
				FileChannel fcout = fout.getChannel();

				ByteBuffer buffer = ByteBuffer.allocate(1024);

				while (true) {
					buffer.clear();

					int r = fcin.read(buffer);

					if (r == -1) {
						break;
					}
//					logger.info("write buffer: " + buffer);
					buffer.flip();
					fcout.write(buffer);
				}
			} catch (Exception e) {
				logger.error("merge error", e);
				e.printStackTrace();
			}
		}
		fout.flush();
		fout.close();
		logger.info("close out stream,use NIO merge chunk file spend: "
				+ (System.currentTimeMillis() - startTime) / 1000 + " s");
	}

	/**
	 * 
	 * @param mpf
	 * @param startPos
	 * @param endPos
	 * @param totalSize
	 * @param fileName
	 * @param tempPath
	 * @return
	 */
	private boolean addChunkFile(MultipartFile mpf, long startPos, long endPos,
			long totalSize, String fileName, String tempPath) {
		try {
			FileCopyUtils.copy(mpf.getBytes(), new FileOutputStream(tempPath
					+ fileName + startPos));

		//鎵�湁chunk鍧椾笂浼犲畬
			if (endPos == totalSize - 1)
				return true;

		} catch (Exception e) {
			e.printStackTrace();
		}
		return false;
	}

	/***************************************************
	 * URL: /rest/controller/get/{value} get(): get file as an attachment
	 * 
	 * @param response
	 *            : passed by the server
	 * @param value
	 *            : value from the URL
	 * @return void
	 ****************************************************/
	@RequestMapping(value = "/get/{value}", method = RequestMethod.GET, headers = "Access-Control-Allow-Origin=*")
	public void get(HttpServletResponse response, @PathVariable String value) {
		FileMeta getFile = files.get(Integer.parseInt(value));
		try {
			response.setContentType(getFile.getFileType());
			response.setHeader("Content-disposition", "attachment; filename=\""
					+ getFile.getFileName() + "\"");
			FileCopyUtils.copy(getFile.getBytes(), response.getOutputStream());
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	
	@RequestMapping(value = "/getFile", method = RequestMethod.GET)
	@ResponseBody
	public String getFile(HttpServletResponse response,
			HttpServletRequest request) {
		JSONObject json = new JSONObject();
		String fileName = request.getParameter("file");
		File dir = new File(tempPath);
		long alreadyUploadBytes = 0;
		for (File f : dir.listFiles()) {
			if (f.getName().startsWith(fileName)) {
				alreadyUploadBytes = alreadyUploadBytes + f.length();
			}
			logger.info("alreadyUpload file: " + fileName);
		}
		logger.info("alreadyUploadBytes: " + alreadyUploadBytes);
		json.put("size", alreadyUploadBytes);
		return json.toString();
	}

	public static byte[] create(String filename) throws Exception {
		InputStream fis = new FileInputStream(filename);
		byte[] buf = new byte[1024];
		MessageDigest com = MessageDigest.getInstance("MD5");
		int num;
		do {
			num = fis.read(buf);
			if (num > 0) {
				com.update(buf, 0, num);
			}
		} while (num != -1);

		fis.close();
		return com.digest();
	}

	public static String getMD5(String filename) throws Exception {
		byte[] b = create(filename);
		String result = "";
		for (int i = 0; i < b.length; i++) {
			result += Integer.toString((b[i] & 0xff) + 0x100, 16).substring(1);
		}
		return result;
	}

	private static String getFilename(String name) {
		for (String cd : name.split(";")) {
			if (cd.trim().startsWith("filename")) {
				String filename = cd.substring(cd.indexOf('=') + 1).trim()
						.replace("\"", "");
				return filename.substring(filename.lastIndexOf('/') + 1)
						.substring(filename.lastIndexOf('\\') + 1); // MSIE fix.
			}
		}
		return null;
	}

}
