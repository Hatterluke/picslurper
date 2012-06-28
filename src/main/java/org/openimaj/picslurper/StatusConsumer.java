package org.openimaj.picslurper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.httpclient.HttpClient;
import org.openimaj.image.ImageUtilities;
import org.openimaj.image.MBFImage;
import org.openimaj.io.FileUtils;
import org.openimaj.io.IOUtils;
import org.openimaj.text.nlp.patterns.URLPatternProvider;
import org.openimaj.twitter.USMFStatus;
import org.openimaj.twitter.collection.StreamJSONStatusList.ReadableWritableJSON;

public class StatusConsumer implements Callable<StatusConsumption>{
	
	final static Pattern urlPattern = new URLPatternProvider().pattern();
	private ReadableWritableJSON status;
	private PicSlurper slurper;

	public StatusConsumer(ReadableWritableJSON status,PicSlurper slurper) {
		this.status = status;
		this.slurper = slurper;
	}
	
	@Override
	@SuppressWarnings("unchecked")
	public StatusConsumption call() throws Exception {
		StatusConsumption cons = new StatusConsumption();
		cons.nTweets=1;
		cons.nURLs=0;
		// match the text URLs
		String text = (String) status.get("text");
		Matcher matcher = urlPattern.matcher((String) text);
		while(matcher.find()){
			cons.nURLs++;
			String urlString = text.substring(matcher.start(),matcher.end());
			File urlOut = resolveURL(new URL(urlString));
			if(urlOut!=null){
				cons.nImages++;
				PicSlurper.updateTweets(urlOut,status);
			}
		}
		// check entities media
		@SuppressWarnings("unchecked")
		List<Map<String,Object>> media = (List<Map<String, Object>>) ((Map<String, Object>)status.get("links")).get("media");
		for (Map<String, Object> map : media) {
			if(map.containsKey("type") && map.get("type").equals("photo")){
				File urlOut = resolveURL(new URL((String) map.get("media_url")));
				if(urlOut!=null){
					cons.nImages++;
				}
			}
		}
		// check the parsed URL entities
		List<Map<String,Object>> urls = (List<Map<String, Object>>) ((Map<String, Object>)status.get("entities")).get("urls");
		for (Map<String, Object> map : media) {
			File urlOut = resolveURL(new URL((String) map.get("expanded_url")));
			if(urlOut!=null){
				cons.nImages++;
			}
		}
		
		
		if(this.slurper.stats) PicSlurper.updateStats(this.slurper.globalStatus,cons);
		return cons;
	}
	
	public static String getMetaRefresh(String html) {
		String meta = null;
		int start = html.toLowerCase().indexOf("<meta http-equiv=\"refresh\" content=\"");
		if (start > -1) {
			start += 36;
			int end = html.indexOf('"', start);
			if (end > -1) {
				meta = html.substring(start, end);
				start = meta.toLowerCase().indexOf("url=");
				if (start > -1) {
					start += 4;
					meta = new String(meta.substring(start));
				}
			}
		}

		return meta;
	}
	
	File resolveURL(URL url) {
		
		MBFImage image = null;
		try {
			String meta = null;
			HttpURLConnection connection = urlAsStream(url);
			if(connection.getContentType().startsWith("text")){
				// check if there is a meta refresh, if so, try to resolve again
				meta = getMetaRefresh(FileUtils.readall(connection.getInputStream()));
				if(meta!=null){
					return resolveURL(new URL(meta));
				}else{
					System.out.println("Resolving url: " + url + " FAILED (text) ");
					return null;//text, can't handle it!
				}
			}
			else{
				// Not text? try reading it as an image!
				image = ImageUtilities.readMBF(connection.getInputStream());
			}
		} catch (Throwable e) { // This input might not be an image! deal with that
			System.out.println("Resolving url: " + url + " FAILED (read fail)");
			return null; 
		}
		File outputDir;
		try {
			outputDir = urlToOutput(url,slurper.outputLocation);
			File outImage = new File(outputDir,"image.png");
			File outStats = new File(outputDir,"status.txt");
			StatusConsumption cons = new StatusConsumption();
			cons.nTweets++;
			PicSlurper.updateStats(outStats,cons);
			ImageUtilities.write(image, outImage);
			System.out.println("Resolving url: " + url + " SUCCESS");
			return outputDir;
		} catch (IOException e) {
		}
		System.out.println("Resolving url: " + url + " FAILED (write fail?)");
		return null;
		
	}
	public static HttpURLConnection urlAsStream(URL url) throws IOException {
//		HttpClient client = new HttpClient();
//		HttpGet httpget = new HttpGet(url);
//		HttpURLConnection conn = (HttpURLConnection)url.openConnection();
//		conn.setConnectTimeout(15000);
//		conn.setReadTimeout(15000);
//		conn.setInstanceFollowRedirects(true);
//		conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows; U; Windows NT 6.0; ru; rv:1.9.0.11) Gecko/2009060215 Firefox/3.0.11 (.NET CLR 3.5.30729)");
//		conn.connect();
		HttpURLConnection conn = (HttpURLConnection)url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows; U; Windows NT 6.0; ru; rv:1.9.0.11) Gecko/2009060215 Firefox/3.0.11 (.NET CLR 3.5.30729)");
        conn.connect();
        
        return conn;
	}

	static synchronized File urlToOutput(URL url, File outputLocation) throws IOException {
		String urlPath = url.getProtocol() + File.separator +
						 url.getHost() + File.separator;
		if(!url.getPath().equals("")) urlPath += url.getPath() + File.separator;
		if(url.getQuery()!= null) urlPath += url.getQuery() + File.separator;
		
		String outPath = outputLocation.getAbsolutePath() + File.separator + urlPath;
		File outFile = new File(outPath);
		if(outFile.exists()){
			if(outFile.isDirectory()) {
				return outFile;
			}
			else{
				createURLOutDir(outFile);
			}
		}else{
			createURLOutDir(outFile);
		}
		return outFile;
	}

	static void createURLOutDir(File outFile) throws IOException {
		if(!((!outFile.exists() || outFile.delete()) && outFile.mkdirs())){
			throw new IOException("Couldn't create URL output: " + outFile.getAbsolutePath());
		}
	}


}
