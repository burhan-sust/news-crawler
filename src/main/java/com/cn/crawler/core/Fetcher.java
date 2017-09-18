package com.cn.crawler.core;


import com.cn.crawler.entities.Link;
import com.cn.crawler.entities.News;
import com.cn.crawler.rules.AbstractExploreRule;
import com.cn.crawler.utils.Utils;
import com.google.gson.Gson;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by burhan on 5/31/17.
 */
public class Fetcher implements Runnable {
    private static final String EXCLUDE_EXT_PATTERN = "([^\\s]+(\\.(?i)(jpg|png|gif|bmp|ttf|xml|json|pdf))$)";
    private static final Logger log = LoggerFactory.getLogger(Fetcher.class);

    private final Crawler crawler;
    private final Agent agent;
    private final Queue queue;
    private final AbstractParser parser;
    private final Data data;
    private final Config.Fetcher config;
    private final int maxDepth;
    private final File crawlPath;
    private final Gson gson = new Gson();

    public Fetcher(Crawler crawler, Agent agent, Queue queue) {
        this.crawler = crawler;
        this.agent = agent;
        this.queue = queue;
        this.parser = agent.getParser();
        this.config = crawler.getConfig().getFetcher();
        this.data = crawler.getData();
        this.maxDepth = crawler.getParams().getDepth();
        String crawlPath = crawler.getParams().getCrawlPath();
        if (!Utils.isNullOrEmpty(crawlPath)) {
            this.crawlPath = new File(crawler.getParams().getCrawlPath());
        } else {
            this.crawlPath = null;
        }
    }

    public Link getNextUrl() {
        synchronized (queue) {
            return queue.poll();
        }
    }

    public Connection.Response fetch(Link link) throws IOException {
        String url = Utils.getEncodedUrl(link.getUrl());
        log.info("Fetching: " + link.getUrl() + ", Queue : " + queue.size());
        Connection.Response response = Jsoup
                .connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 6.1; Win64; x64; rv:25.0) Gecko/20100101 Firefox/25.0")
                .referrer("http://www.google.com")
                .timeout(config.getTimeout())
                .execute();
        return response;
    }

    private boolean isValidResposeType(String responseType) {
        return responseType.split(";")[0].equalsIgnoreCase("text/html");
    }

    private boolean isValidUrl(String url) {
        url = url.toLowerCase();
        Pattern pattern = Pattern.compile(EXCLUDE_EXT_PATTERN);
        Matcher matcher = pattern.matcher(url);
        if (matcher.matches()) {
            return false;
        }
        return true;
    }

    public void explore(Link link) throws IOException {
        Connection.Response response = fetch(link);
        if (!isValidResposeType(response.contentType())) {
            return;
        }
        Document doc = response.parse();
        // calculate hash for link
        try {
            String hash = Utils.hash(doc.outerHtml());

            if (link.getStatus() == Status.V) {
                if (hash.equals(link.getHash())) {
                    // content has not changed yet
                    log.info("No change on url : " + link.getUrl());
                    return;
                }
            }
            link.setHash(hash);
        } catch (NoSuchAlgorithmException e) {
            log.error("Failed to calculate hash for link : " + link);
        }
        try {
            News news = parser.parse(link, doc);
            if (news != null) {
                save(link, news);
                link.setNews(true);
                log.info("Found article on : " + link.getUrl() + ", Queue : " + queue.size());
            }
        } catch (ParseException e) {
            log.error("Failed to parse link : " + link);
        }
        if (link.getDepth() < maxDepth) {
            Elements linkElements = doc.select("a[href]");
            for (Element linkElement : linkElements) {
                String url = linkElement.absUrl("href");
                if (!Utils.isNullOrEmpty(url) && isValidUrl(url)) {
                    try {
                        Link l = new Link(url, link.getDepth() + 1);
                        queue.add(l);
                    } catch (MalformedURLException e) {
                        log.error(e.getMessage() + link);
                    }
                }
            }
        }
    }

    private void save(Link link, News news) {
        log.debug(" - Saving: " + link.getUrl());
        if (crawlPath != null) {
            try {
                save2File(link, news);
            } catch (IOException e) {
                log.error("Failed to save news to file. " + e.getMessage());
            }
        }
        data.saveNews(news);
    }

    private void save2File(Link link, News news) throws IOException {
        String path = crawlPath.getAbsolutePath() + File.separator + link.getHost() + File.separator + news.getId();
        String json = gson.toJson(news);
        Utils.save2File(path, json);
    }


    @Override
    public void run() {
        log.info("Running fetcher on : " + queue.getHost());
        while (true) {
            Link link = getNextUrl();
            if (link == null && queue.size() < 1) {
                log.info("Queue empty. Fetcher shutting down on : " + queue.getHost());
                break;
            }
            try {
                explore(link);
                queue.setVisited(link);
                Thread.sleep(config.getDelay());
            } catch (IOException e) {
                queue.setError(link);
                log.error(link + e.getMessage());
                e.printStackTrace();
            } catch (InterruptedException e) {
                queue.setError(link);
                log.error(link + e.getMessage());
                e.printStackTrace();
            }

        }
    }

}
