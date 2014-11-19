/* 
 * ScreenSlicer (TM) -- automatic, zero-config web scraping (TM)
 * Copyright (C) 2013-2014 Machine Publishers, LLC
 * ops@machinepublishers.com | screenslicer.com | machinepublishers.com
 * 717 Martin Luther King Dr W Ste I, Cincinnati, Ohio 45220
 *
 * You can redistribute this program and/or modify it under the terms of the
 * GNU Affero General Public License version 3 as published by the Free
 * Software Foundation. Additional permissions or commercial licensing may be
 * available--see LICENSE file or contact Machine Publishers, LLC for details.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License version 3
 * for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * version 3 along with this program. If not, see <http://www.gnu.org/licenses/>.
 * 
 * For general details about how to investigate and report license violations,
 * please see: https://www.gnu.org/licenses/gpl-violation.html
 * and email the author: ops@machinepublishers.com
 * Keep in mind that paying customers have more rights than the AGPL alone offers.
 */
package com.screenslicer.core.scrape;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;
import org.jsoup.nodes.Node;
import org.openqa.selenium.io.TemporaryFilesystem;
import org.openqa.selenium.remote.BrowserDriver;
import org.openqa.selenium.remote.BrowserDriver.Profile;
import org.openqa.selenium.remote.BrowserDriver.Retry;

import com.screenslicer.api.datatype.HtmlNode;
import com.screenslicer.api.datatype.Proxy;
import com.screenslicer.api.datatype.SearchResult;
import com.screenslicer.api.datatype.UrlTransform;
import com.screenslicer.api.request.Fetch;
import com.screenslicer.api.request.FormLoad;
import com.screenslicer.api.request.FormQuery;
import com.screenslicer.api.request.KeywordQuery;
import com.screenslicer.api.request.Query;
import com.screenslicer.api.request.Request;
import com.screenslicer.common.CommonUtil;
import com.screenslicer.common.Log;
import com.screenslicer.common.Random;
import com.screenslicer.core.scrape.Proceed.End;
import com.screenslicer.core.scrape.neural.NeuralNetManager;
import com.screenslicer.core.scrape.type.SearchResults;
import com.screenslicer.core.service.ScreenSlicerBatch;
import com.screenslicer.core.util.Util;
import com.screenslicer.webapp.WebApp;

import de.l3s.boilerpipe.extractors.NumWordsRulesExtractor;

public class Scrape {
  public static class ActionFailed extends Exception {
    private static final long serialVersionUID = 1L;

    public ActionFailed() {
      super();
    }

    public ActionFailed(Throwable nested) {
      super(nested);
      Log.exception(nested);
    }

    public ActionFailed(String message) {
      super(message);
    }
  }

  public static class Cancelled extends Exception {

  }

  private static volatile BrowserDriver driver = null;
  private static final int MIN_SCRIPT_TIMEOUT = 30;
  private static final int MAX_INIT = 1000;
  private static final int HANG_TIME = 10 * 60 * 1000;
  private static final int RETRIES = 7;
  private static final long WAIT = 2000;
  private static AtomicLong latestThread = new AtomicLong();
  private static AtomicLong curThread = new AtomicLong();
  private static final Object cacheLock = new Object();
  private static final Map<String, List> nextResults = new HashMap<String, List>();
  private static List<String> cacheKeys = new ArrayList<String>();
  private static final int LIMIT_CACHE = 5000;
  private static final int MAX_CACHE = 500;
  private static final int CLEAR_CACHE = 250;
  private static AtomicBoolean done = new AtomicBoolean(false);
  private static final Object progressLock = new Object();
  private static String progress1Key = "";
  private static String progress2Key = "";
  private static String progress1 = "";
  private static String progress2 = "";

  public static final List<SearchResult> WAITING = new ArrayList<SearchResult>();

  public static void init() {
    NeuralNetManager.reset(new File("./resources/neural/config"));
    start(new Request());
    done.set(true);
  }

  private static void start(Request req) {
    Proxy[] proxies = CommonUtil.isEmpty(req.proxies) ? new Proxy[] { req.proxy } : req.proxies;
    for (int i = 0; i < RETRIES; i++) {
      try {
        Profile profile = new Profile(new File("./firefox-profile"));
        if (!"synthetic".equals(System.getProperty("slicer_events"))) {
          profile.setAlwaysLoadNoFocusLib(true);
          profile.setEnableNativeEvents(true);
        }
        for (int curProxy = 0; curProxy < proxies.length; curProxy++) {
          Proxy proxy = proxies[curProxy];
          if (proxy != null) {
            if (!CommonUtil.isEmpty(proxy.username) || !CommonUtil.isEmpty(proxy.password)) {
              String user = proxy.username == null ? "" : proxy.username;
              String pass = proxy.password == null ? "" : proxy.password;
              profile.setPreference("extensions.closeproxyauth.authtoken",
                  Base64.encodeBase64String((user + ":" + pass).getBytes("utf-8")));
            } else {
              profile.setPreference("extensions.closeproxyauth.authtoken", "");
            }
            if (Proxy.TYPE_ALL.equals(proxy.type)
                || Proxy.TYPE_SOCKS_5.equals(proxy.type)
                || Proxy.TYPE_SOCKS_4.equals(proxy.type)) {
              profile.setPreference("network.proxy.type", 1);
              profile.setPreference("network.proxy.socks", proxy.ip);
              profile.setPreference("network.proxy.socks_port", proxy.port);
              profile.setPreference("network.proxy.socks_remote_dns", true);
              profile.setPreference("network.proxy.socks_version",
                  Proxy.TYPE_ALL.equals(proxy.type) || Proxy.TYPE_SOCKS_5.equals(proxy.type) ? 5 : 4);
            }
            if (Proxy.TYPE_ALL.equals(proxy.type)
                || Proxy.TYPE_SSL.equals(proxy.type)) {
              profile.setPreference("network.proxy.type", 1);
              profile.setPreference("network.proxy.ssl", proxy.ip);
              profile.setPreference("network.proxy.ssl_port", proxy.port);
            }
            if (Proxy.TYPE_ALL.equals(proxy.type)
                || Proxy.TYPE_HTTP.equals(proxy.type)) {
              profile.setPreference("network.proxy.type", 1);
              profile.setPreference("network.proxy.http", proxy.ip);
              profile.setPreference("network.proxy.http_port", proxy.port);
            }
          }
        }
        if (!CommonUtil.isEmpty(req.httpHeaders)) {
          profile.setPreference("extensions.screenslicer.headers",
              Base64.encodeBase64String(CommonUtil.gson.toJson(
                  req.httpHeaders, CommonUtil.stringType).getBytes("utf-8")));
        }
        if (req.browserPrefs != null) {
          for (Map.Entry<String, Object> entry : req.browserPrefs.entrySet()) {
            if (entry.getValue() instanceof Integer) {
              profile.setPreference(entry.getKey(), (Integer) entry.getValue());
            } else if (entry.getValue() instanceof Double) {
              profile.setPreference(entry.getKey(), (int) Math.rint(((Double) entry.getValue())));
            } else if (entry.getValue() instanceof Boolean) {
              profile.setPreference(entry.getKey(), (Boolean) entry.getValue());
            } else if (entry.getValue() instanceof String) {
              profile.setPreference(entry.getKey(), (String) entry.getValue());
            }
          }
        }
        if (req.timeout > MIN_SCRIPT_TIMEOUT) {
          profile.setPreference("dom.max_chrome_script_run_time", req.timeout);
          profile.setPreference("dom.max_script_run_time", req.timeout);
        }
        driver = new BrowserDriver(profile);
        try {
          driver.manage().timeouts().pageLoadTimeout(req.timeout, TimeUnit.SECONDS);
          driver.manage().timeouts().setScriptTimeout(req.timeout, TimeUnit.SECONDS);
          driver.manage().timeouts().implicitlyWait(0, TimeUnit.SECONDS);
        } catch (Throwable t) {
          //marionette connection doesn't allow setting timeouts
        }
        break;
      } catch (Throwable t1) {
        if (driver != null) {
          try {
            forceQuit();
            driver = null;
          } catch (Throwable t2) {
            Log.exception(t2);
          }
        }
        Log.exception(t1);
      }
    }
  }

  public static void forceQuit() {
    try {
      if (driver != null) {
        driver.kill();
        Util.driverSleepStartup();
      }
    } catch (Throwable t) {
      Log.exception(t);
    }
    try {
      TemporaryFilesystem tempFS = TemporaryFilesystem.getDefaultTmpFS();
      tempFS.deleteTemporaryFiles();
    } catch (Throwable t) {
      Log.exception(t);
    }
  }

  private static void restart(Request req) {
    try {
      forceQuit();
    } catch (Throwable t) {
      Log.exception(t);
    }
    try {
      driver = null;
    } catch (Throwable t) {
      Log.exception(t);
    }
    start(req);
  }

  private static void push(String mapKey, List results) {
    synchronized (cacheLock) {
      nextResults.put(mapKey, results);
      if (nextResults.size() == LIMIT_CACHE) {
        List<String> toRemove = new ArrayList<String>();
        for (Map.Entry<String, List> entry : nextResults.entrySet()) {
          if (!cacheKeys.contains(entry.getKey())
              && !entry.getKey().equals(mapKey)) {
            toRemove.add(entry.getKey());
          }
        }
        for (String key : toRemove) {
          nextResults.remove(key);
        }
        nextResults.put(mapKey, results);
      }
      if (results != null && !results.isEmpty()) {
        if (cacheKeys.size() == MAX_CACHE) {
          List<String> newCache = new ArrayList<String>();
          for (int i = 0; i < CLEAR_CACHE; i++) {
            nextResults.remove(cacheKeys.get(i));
          }
          for (int i = CLEAR_CACHE; i < MAX_CACHE; i++) {
            newCache.add(cacheKeys.get(i));
          }
          cacheKeys = newCache;
        }
        cacheKeys.add(mapKey);
      }
    }
  }

  public static List<SearchResult> cached(String mapKey) {
    synchronized (cacheLock) {
      if (nextResults.containsKey(mapKey)) {
        List<SearchResult> ret = nextResults.get(mapKey);
        if (ret == null) {
          return WAITING;
        }
        return ret;
      } else {
        return null;
      }
    }
  }

  public static boolean busy() {
    return !done.get();
  }

  public static String progress(String mapKey) {
    synchronized (progressLock) {
      if (progress1Key.equals(mapKey)) {
        return progress1;
      }
      if (progress2Key.equals(mapKey)) {
        return progress2;
      }
      return "";
    }
  }

  private static String toCacheUrl(String url, boolean fallback) {
    if (url == null) {
      return null;
    }
    if (fallback) {
      return "http://webcache.googleusercontent.com/search?q=cache:" + url.split("://")[1];
    }
    String[] urlParts = url.split("://")[1].split("/", 2);
    String urlLhs = urlParts[0];
    String urlRhs = urlParts.length > 1 ? urlParts[1] : "";
    return "http://" + urlLhs + ".nyud.net:8080/" + urlRhs;
  }

  private static void fetch(BrowserDriver driver, Request req, Query query, Query recQuery,
      SearchResults results, int depth, SearchResults recResults,
      Map<String, Object> cache) throws ActionFailed {
    boolean terminate = false;
    try {
      String origHandle = driver.getWindowHandle();
      String origUrl = driver.getCurrentUrl();
      String newHandle = null;
      if (query.fetchCached) {
        newHandle = Util.newWindow(driver, depth == 0);
      }
      try {
        for (int i = query.currentResult(); i < results.size(); i++) {
          if (query.requireResultAnchor && !isUrlValid(results.get(i).url)
              && Util.uriScheme.matcher(results.get(i).url).matches()) {
            results.get(i).close();
            query.markResult(i + 1);
            continue;
          }
          if (ScreenSlicerBatch.isCancelled(req.runGuid)) {
            return;
          }
          Log.info("Fetching URL " + results.get(i).url + ". Cached: " + query.fetchCached, false);
          try {
            results.get(i).pageHtml = getHelper(driver, query.throttle,
                CommonUtil.parseFragment(results.get(i).urlNode, false), results.get(i).url, query.fetchCached,
                req.runGuid, query.fetchInNewWindow, depth == 0 && query == null,
                query == null ? null : query.postFetchClicks);
            if (!CommonUtil.isEmpty(results.get(i).pageHtml)) {
              try {
                results.get(i).pageText = NumWordsRulesExtractor.INSTANCE.getText(results.get(i).pageHtml);
              } catch (Throwable t) {
                results.get(i).pageText = null;
                Log.exception(t);
              }
            }
            if (recQuery != null) {
              recResults.addPage(scrape(recQuery, req, depth + 1, false, cache));
            }
            if (query.collapse) {
              results.get(i).close();
            }
            query.markResult(i + 1);
          } catch (Retry r) {
            terminate = true;
            throw r;
          } catch (Throwable t) {
            terminate = true;
            throw new ActionFailed(t);
          }
          try {
            if (!driver.getWindowHandle().equals(origHandle)) {
              driver.close();
              driver.switchTo().window(origHandle);
              driver.switchTo().defaultContent();
            } else if (!query.fetchInNewWindow) {
              Util.get(driver, origUrl, true, depth == 0);
              SearchResults.revalidate(driver, false);
            }
          } catch (Retry r) {
            terminate = true;
            throw r;
          } catch (Throwable t) {
            terminate = true;
            throw new ActionFailed(t);
          }
        }
      } catch (Retry r) {
        terminate = true;
        throw r;
      } catch (Throwable t) {
        terminate = true;
        throw new ActionFailed(t);
      } finally {
        if (!terminate) {
          if (!query.fetchInNewWindow || (query.fetchCached && origHandle.equals(newHandle))) {
            if (query.fetchInNewWindow) {
              Log.exception(new Throwable("Failed opening new window"));
            }
            Util.get(driver, origUrl, true, depth == 0);
          } else {
            Util.handleNewWindows(driver, origHandle, depth == 0);
          }
        }
      }
    } catch (Retry r) {
      terminate = true;
      throw r;
    } catch (Throwable t) {
      terminate = true;
      throw new ActionFailed(t);
    } finally {
      if (!terminate) {
        Util.driverSleepRand(query.throttle);
      }
    }
  }

  private static String getHelper(final BrowserDriver driver, final boolean throttle,
      final Node urlNode, final String url, final boolean p_cached, final String runGuid,
      final boolean toNewWindow, final boolean init, final HtmlNode[] postFetchClicks) {
    if (!CommonUtil.isEmpty(url) || urlNode != null) {
      final Object resultLock = new Object();
      final String initVal;
      final String[] result;
      synchronized (resultLock) {
        initVal = Random.next();
        result = new String[] { initVal };
      }
      final AtomicBoolean started = new AtomicBoolean();
      Thread thread = new Thread(new Runnable() {
        @Override
        public void run() {
          boolean retry = false;
          started.set(true);
          boolean cached = p_cached;
          String newHandle = null;
          String origHandle = null;
          try {
            origHandle = driver.getWindowHandle();
            String content = null;
            if (!cached) {
              try {
                Util.get(driver, url, urlNode, false, toNewWindow, init);
              } catch (Retry r) {
                retry = true;
                throw r;
              } catch (Throwable t) {
                if (urlNode != null) {
                  Util.newWindow(driver, init);
                }
                Util.get(driver, url, false, init);
              }
              if (urlNode != null) {
                newHandle = driver.getWindowHandle();
              }
              Util.doClicks(driver, postFetchClicks, null);
              content = driver.getPageSource();
              if (CommonUtil.isEmpty(content)) {
                cached = true;
              }
            }
            if (cached) {
              if (ScreenSlicerBatch.isCancelled(runGuid)) {
                return;
              }
              try {
                Util.get(driver, toCacheUrl(url, false), false, init);
              } catch (Retry r) {
                retry = true;
                throw r;
              } catch (Throwable t) {
                Util.get(driver, toCacheUrl(url, true), false, init);
              }
              content = driver.getPageSource();
            }
            content = Util.clean(content, driver.getCurrentUrl()).outerHtml();
            if (WebApp.DEBUG) {
              try {
                FileUtils.writeStringToFile(new File("./" + System.currentTimeMillis() + ".log.fetch"), content, "utf-8");
              } catch (IOException e) {}
            }
            //TODO make iframes work
            //            if (!CommonUtil.isEmpty(content)) {
            //              Document doc = Jsoup.parse(content);
            //              Elements docFrames = doc.getElementsByTag("iframe");
            //              List<WebElement> iframes = driver.findElementsByTagName("iframe");
            //              int cur = 0;
            //              for (WebElement iframe : iframes) {
            //                try {
            //                  driver.switchTo().frame(iframe);
            //                  String frameSrc = driver.getPageSource();
            //                  if (!CommonUtil.isEmpty(frameSrc) && cur < docFrames.size()) {
            //                    docFrames.get(cur).html(
            //                        Util.outerHtml(Jsoup.parse(frameSrc).body().childNodes()));
            //                  }
            //                } catch (Throwable t) {
            //                  Log.exception(t);
            //                }
            //                ++cur;
            //              }
            //              driver.switchTo().defaultContent();
            //              content = doc.outerHtml();
            //            }
            synchronized (resultLock) {
              result[0] = content;
            }
          } catch (Retry r) {
            retry = true;
            throw r;
          } catch (Throwable t) {
            Log.exception(t);
          } finally {
            synchronized (resultLock) {
              if (initVal.equals(result[0])) {
                result[0] = null;
              }
            }
            if (!retry) {
              Util.driverSleepRand(throttle);
              if (init && newHandle != null && origHandle != null) {
                try {
                  Util.handleNewWindows(driver, origHandle, true);
                } catch (Retry r) {
                  throw r;
                } catch (Throwable t) {
                  Log.exception(t);
                }
              }
            }
          }
        }
      });
      thread.start();
      try {
        while (!started.get()) {
          try {
            Thread.sleep(WAIT);
          } catch (Throwable t) {}
        }
        thread.join(HANG_TIME);
        synchronized (resultLock) {
          if (initVal.equals(result[0])) {
            Log.exception(new Exception("Browser is hanging"));
            try {
              thread.interrupt();
            } catch (Throwable t) {
              Log.exception(t);
            }
            throw new Retry();
          }
          return result[0];
        }
      } catch (Retry r) {
        throw r;
      } catch (Throwable t) {
        Log.exception(t);
      }
    }
    return null;
  }

  public static String get(Fetch fetch, Request req) {
    if (!isUrlValid(fetch.url)) {
      return null;
    }
    long myThread = latestThread.incrementAndGet();
    while (myThread != curThread.get() + 1
        || !done.compareAndSet(true, false)) {
      try {
        Thread.sleep(WAIT);
      } catch (Exception e) {
        Log.exception(e);
      }
    }
    if (!req.continueSession) {
      restart(req);
    }
    Log.info("Get URL " + fetch.url + ". Cached: " + fetch.fetchCached, false);
    String resp = "";
    try {
      resp = getHelper(driver, fetch.throttle, null, fetch.url, fetch.fetchCached, req.runGuid, true, true, fetch.postFetchClicks);
    } catch (Retry r) {
      throw r;
    } catch (Throwable t) {
      Log.exception(t);
    } finally {
      curThread.incrementAndGet();
      done.set(true);
    }
    return resp;
  }

  private static SearchResults filterResults(SearchResults results, String[] whitelist,
      String[] patterns, HtmlNode[] urlNodes, UrlTransform[] urlTransforms, boolean forExport) {
    if (results == null) {
      return SearchResults.newInstance(true);
    }
    SearchResults ret;
    results = Util.transformUrls(results, urlTransforms, forExport);
    if ((whitelist == null || whitelist.length == 0)
        && (patterns == null || patterns.length == 0)
        && (urlNodes == null || urlNodes.length == 0)) {
      ret = results;
    } else {
      List<SearchResult> filtered = new ArrayList<SearchResult>();
      for (int i = 0; i < results.size(); i++) {
        if (!Util.isResultFiltered(results.get(i), whitelist, patterns, urlNodes)) {
          filtered.add(results.get(i));
        }
      }
      if (filtered.isEmpty() && !results.isEmpty()) {
        Log.warn("Filtered every url, e.g., " + results.get(0).url);
      }
      ret = SearchResults.newInstance(true, filtered, results);
    }
    return ret;
  }

  public static List<HtmlNode> loadForm(FormLoad context, Request req) throws ActionFailed {
    if (!isUrlValid(context.site)) {
      return new ArrayList<HtmlNode>();
    }
    long myThread = latestThread.incrementAndGet();
    while (myThread != curThread.get() + 1
        || !done.compareAndSet(true, false)) {
      try {
        Thread.sleep(WAIT);
      } catch (Exception e) {
        Log.exception(e);
      }
    }
    if (!req.continueSession) {
      restart(req);
    }
    try {
      List<HtmlNode> ret = null;
      try {
        ret = QueryForm.load(driver, context, true);
      } catch (Retry r) {
        throw r;
      } catch (Throwable t) {
        if (!req.continueSession) {
          restart(req);
        }
        ret = QueryForm.load(driver, context, true);
      }
      return ret;
    } finally {
      curThread.incrementAndGet();
      done.set(true);
    }
  }

  private static void handlePage(Request req, Query query, int page, int depth,
      SearchResults results, SearchResults recResults, List<String> resultPages,
      Map<String, Object> cache) throws ActionFailed, End {
    if (query.extract) {
      SearchResults newResults;
      try {
        newResults = ProcessPage.perform(driver, page, query);
      } catch (Retry r) {
        SearchResults.revalidate(driver, true);
        newResults = ProcessPage.perform(driver, page, query);
      }
      newResults = filterResults(newResults, query.urlWhitelist, query.urlPatterns,
          query.urlMatchNodes, query.urlTransforms, false);
      if (results.isDuplicatePage(newResults)) {
        throw new End();
      }
      if (query.results > 0 && results.size() + newResults.size() > query.results) {
        int remove = results.size() + newResults.size() - query.results;
        for (int i = 0; i < remove && !newResults.isEmpty(); i++) {
          newResults.remove(newResults.size() - 1);
        }
      }
      if (query.fetch) {
        fetch(driver, req, query,
            query.keywordQuery == null ? (query.formQuery == null ? null : query.formQuery) : query.keywordQuery,
            newResults, depth, recResults, cache);
      }
      if (query.collapse) {
        for (int i = 0; i < newResults.size(); i++) {
          newResults.get(i).close();
        }
      }
      results.addPage(newResults);
    } else {
      resultPages.add(Util.clean(driver.getPageSource(), driver.getCurrentUrl()).outerHtml());
    }
  }

  public static List<SearchResult> scrape(Query query, Request req) {
    long myThread = latestThread.incrementAndGet();
    while (myThread != curThread.get() + 1
        || !done.compareAndSet(true, false)) {
      try {
        Thread.sleep(WAIT);
      } catch (Exception e) {
        Log.exception(e);
      }
    }
    if (!req.continueSession) {
      restart(req);
    }
    try {
      Map<String, Object> cache = new HashMap<String, Object>();
      SearchResults ret = null;
      for (int i = 0; i < MAX_INIT; i++) {
        try {
          ret = scrape(query, req, 0, i + 1 == MAX_INIT, cache);
          Log.info("Scrape finished");
          return ret.drain();
        } catch (ActionFailed t) {
          Log.warn("Reinitializing state and resuming scrape...");
          restart(req);
        }
      }
      return null;
    } finally {
      curThread.incrementAndGet();
      done.set(true);
    }
  }

  private static SearchResults scrape(Query query, Request req, int depth, boolean fallback, Map<String, Object> cache)
      throws ActionFailed {
    CommonUtil.clearStripCache();
    Util.clearOuterHtmlCache();
    SearchResults results;
    SearchResults recResults;
    List<String> resultPages;
    if (cache.containsKey(Integer.toString(depth))) {
      Map<String, Object> curCache = (Map<String, Object>) cache.get(Integer.toString(depth));
      results = (SearchResults) curCache.get("results");
      recResults = (SearchResults) curCache.get("recResults");
      resultPages = (List<String>) curCache.get("resultPages");
    } else {
      Map<String, Object> curCache = new HashMap<String, Object>();
      cache.put(Integer.toString(depth), curCache);
      results = SearchResults.newInstance(true);
      curCache.put("results", results);
      recResults = SearchResults.newInstance(false);
      curCache.put("recResults", recResults);
      resultPages = new ArrayList<String>();
      curCache.put("resultPages", resultPages);
    }
    try {
      if (ScreenSlicerBatch.isCancelled(req.runGuid)) {
        throw new Cancelled();
      }
      if (query.isFormQuery()) {
        Log.info("FormQuery for URL " + query.site, false);
        try {
          QueryForm.perform(driver, (FormQuery) query, depth == 0);
        } catch (Throwable e) {
          if (depth == 0) {
            restart(req);
          }
          QueryForm.perform(driver, (FormQuery) query, depth == 0);
        }
      } else {
        Log.info("KewordQuery for URL " + query.site + ". Query: " + ((KeywordQuery) query).keywords, false);
        try {
          QueryKeyword.perform(driver, (KeywordQuery) query, depth == 0);
        } catch (Throwable e) {
          if (depth == 0) {
            restart(req);
          }
          QueryKeyword.perform(driver, (KeywordQuery) query, depth == 0);
        }
      }
      if (ScreenSlicerBatch.isCancelled(req.runGuid)) {
        throw new Cancelled();
      }
      String priorProceedLabel = null;
      for (int page = 1; (page <= query.pages || query.pages <= 0)
          && (results.size() < query.results || query.results <= 0); page++) {
        if (ScreenSlicerBatch.isCancelled(req.runGuid)) {
          throw new Cancelled();
        }
        if (page > 1) {
          if (!query.fetch) {
            try {
              Util.driverSleepRand(query.throttle);
            } catch (Throwable t) {
              Log.exception(t);
            }
          }
          try {
            priorProceedLabel = Proceed.perform(driver, query.proceedClicks, page, priorProceedLabel);
          } catch (Retry r) {
            SearchResults.revalidate(driver, true);
            priorProceedLabel = Proceed.perform(driver, query.proceedClicks, page, priorProceedLabel);
          }
          if (ScreenSlicerBatch.isCancelled(req.runGuid)) {
            throw new Cancelled();
          }
        }
        if (query.currentPage() + 1 == page) {
          try {
            handlePage(req, query, page, depth, results, recResults, resultPages, cache);
          } catch (Retry r) {
            SearchResults.revalidate(driver, true);
            handlePage(req, query, page, depth, results, recResults, resultPages, cache);
          }
          query.markPage(page);
          query.markResult(0);
        }
      }
    } catch (End e) {
      Log.info("Reached end of results", false);
    } catch (Cancelled c) {
      Log.info("Cancellation requested.");
    } catch (Throwable t) {
      if (fallback) {
        Log.warn("Too many errors. Finishing scrape...");
      } else {
        throw new ActionFailed(t);
      }
    }
    cache.remove(Integer.toString(depth));
    if (query.extract) {
      if (recResults.isEmpty()) {
        return filterResults(results, query.urlWhitelist,
            query.urlPatterns, query.urlMatchNodes, query.urlTransforms, true);
      }
      if (query.collapse) {
        for (int i = 0; i < results.size(); i++) {
          results.get(i).remove();
        }
      }
      return recResults;
    }
    List<SearchResult> pages = new ArrayList<SearchResult>();
    for (String page : resultPages) {
      SearchResult r = new SearchResult();
      r.html = page;
      pages.add(r);
    }
    return SearchResults.newInstance(false, pages, null);
  }

  private static boolean isUrlValid(String url) {
    return !CommonUtil.isEmpty(url) && (url.startsWith("https://") || url.startsWith("http://"));
  }

  public static List<SearchResult> scrape(String url, final String query, final int pages, final String mapKey1, final String mapKey2) {
    if (!isUrlValid(url)) {
      return new ArrayList<SearchResult>();
    }
    if (!done.compareAndSet(true, false)) {
      return null;
    }
    restart(new Request());
    CommonUtil.clearStripCache();
    Util.clearOuterHtmlCache();
    List<SearchResult> results = new ArrayList<SearchResult>();
    final KeywordQuery keywordQuery = new KeywordQuery();
    try {
      synchronized (progressLock) {
        progress1Key = mapKey1;
        progress2Key = mapKey2;
        progress1 = "Page 1 progress: performing search query...";
        progress2 = "Page 2 progress: waiting for prior page extraction to finish...";
      }
      push(mapKey1, null);
      keywordQuery.site = url;
      keywordQuery.keywords = query;
      QueryKeyword.perform(driver, keywordQuery, true);
      synchronized (progressLock) {
        progress1 = "Page 1 progress: extracting results...";
      }
      results.addAll(ProcessPage.perform(driver, 1, keywordQuery).drain());
      synchronized (progressLock) {
        progress1 = "";
      }
    } catch (Throwable t) {
      Log.exception(t);
      push(mapKey1, results);
      synchronized (progressLock) {
        progress1 = "";
        progress2 = "Page 2 progress: prior page extraction was not completed.";
      }
      done.set(true);
      return results;
    }
    try {
      push(mapKey2, null);
      push(mapKey1, results);
    } catch (Throwable t) {
      Log.exception(t);
      synchronized (progressLock) {
        progress1 = "";
        progress2 = "Page 2 progress: prior page extraction was not completed.";
      }
      done.set(true);
      return results;
    }
    new Thread(new Runnable() {
      @Override
      public void run() {
        List<SearchResult> next = new ArrayList<SearchResult>();
        try {
          synchronized (progressLock) {
            progress2 = "Page 2 progress: getting page...";
          }
          Proceed.perform(driver, null, 2, query);
          synchronized (progressLock) {
            progress2 = "Page 2 progress: extracting results...";
          }
          next.addAll(ProcessPage.perform(driver, 2, keywordQuery).drain());
        } catch (End e) {
          Log.info("Reached end of results", false);
        } catch (Throwable t) {
          Log.exception(t);
        }
        finally {
          push(mapKey2, next);
          synchronized (progressLock) {
            progress2 = "";
          }
          done.set(true);
        }
      }
    }).start();
    return results;
  }
}
