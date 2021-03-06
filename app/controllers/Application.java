package controllers;

import com.dropbox.core.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.ConfigFactory;
import com.typesafe.plugin.RedisPlugin;
import org.apache.commons.codec.binary.Hex;
import org.imgscalr.Scalr;
import play.Logger;
import play.Play;
import play.libs.F.Promise;
import play.mvc.BodyParser;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import views.html.done;
import views.html.index;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

public class Application extends Controller {

    private static Logger.ALogger log = play.Logger.of("Markdown Webhooks Example Java");
    private static  DbxWebAuth webAuth ;
    private static Jedis redisClient;
    private static DbxRequestConfig dropboxConfig;

    private static final String APP_KEY = ConfigFactory.load().getString("app.key");
    private static final String APP_SECRET = ConfigFactory.load().getString("app.secret");
    private static JedisPool pool;
    public static Result index() {
        return ok(index.render());
    }

    static {

        DbxAppInfo appInfo = new DbxAppInfo(APP_KEY, APP_SECRET);

        dropboxConfig = new DbxRequestConfig(
                "Markdown Webhooks Example Java", Locale.getDefault().toString());

        String sessionKey = "iojafasdoi83nxopef-hvak"; //todo read from config
        Map<String, String> sessionMap = new HashMap<String,String>();
        Http.Session session = new Http.Session(sessionMap);
        String redirectUrl = "";
        if (Http.Context.current().request().host().startsWith("127.0.0.1") ||
                Http.Context.current().request().host().startsWith("localhost")) {
            redirectUrl = routes.Application.oAuthCallBack().
                    absoluteURL(Http.Context.current().request());
        }
        else {
            redirectUrl = routes.Application.oAuthCallBack().
                    absoluteURL(Http.Context.current().request(), true);
        }
        DbxSessionStore csrfSessionStore = new DbxPlaySessionStore(session,sessionKey);
        webAuth = new DbxWebAuth(dropboxConfig,appInfo,redirectUrl,csrfSessionStore);

        pool = Play.application().plugin(RedisPlugin.class).jedisPool();


    }

    public static Result getFlow() {
        return redirect(webAuth.start());
    }

    public static Promise<Result> oAuthCallBack() {
        DbxAuthFinish authFinish;
        try {
            play.mvc.Http.Request request = request();
            log.info("***Request param are ****",request.toString());
            authFinish = webAuth.finish(request.queryString());
        }
        catch (DbxWebAuth.BadRequestException ex) {
            log.error("On /dropbox-auth-finish: Bad request: " + ex.getMessage());
            return Promise.pure(status(400, " Bad request."));
        }
        catch (DbxWebAuth.BadStateException ex) {
            // Send them back to the start of the auth flow.
            // use the same csrf key both the times
            log.info("Bad state exception. Redirecting again.");
            String redirectUrl = "";
            if (Http.Context.current().request().host().startsWith("127.0.0.1")) {
                redirectUrl = routes.Application.oAuthCallBack().
                        absoluteURL(Http.Context.current().request());
            }
            else {
                redirectUrl = routes.Application.oAuthCallBack().
                        absoluteURL(Http.Context.current().request(), true);
            }
            return Promise.pure(redirect(redirectUrl));
        }
        catch (DbxWebAuth.CsrfException ex) {
            log.error("On /dropbox-auth-finish: CSRF mismatch: " + ex.getMessage());
            return Promise.pure(notFound());
        }
        catch (DbxWebAuth.NotApprovedException ex) {
            // When Dropbox asked "Do you want to allow this app to access your
            // Dropbox account?", the user clicked "No".
            return Promise.pure(unauthorized());
        }
        catch (DbxWebAuth.ProviderException ex) {
            log.error("On /dropbox-auth-finish: Auth failed: " + ex.getMessage());
            return Promise.pure(status(503, "Error communicating with Dropbox."));
        }
        catch (DbxException ex) {
            log.error("On /dropbox-auth-finish: Error getting token: " + ex.getMessage());
            return Promise.pure(status(503, "Error communicating with Dropbox."));
        }
        String accessToken = authFinish.accessToken;
        String uid = authFinish.userId;
        String urlState = authFinish.urlState;
        redisClient = pool.getResource();
        redisClient.hset("tokens", uid, accessToken);
        play.Play.application().plugin(RedisPlugin.class).jedisPool().returnResource(redisClient);
        Promise<Boolean> performConversionForUser = Promise.promise(() -> processUser(uid));
        return performConversionForUser.map(aBoolean -> {
            if (aBoolean) {
                return ok(done.render());
            }
            else {
                return internalServerError();
            }
        });
    }

    private static Boolean validateRequest() {
        String[] signatureArray = request().headers().get("X-Dropbox-Signature");
        String signature = signatureArray[0];
        try {
            Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(APP_SECRET.getBytes(), "HmacSHA256");
            sha256_HMAC.init(secretKey);
            Http.RequestBody requestBody = request().body();
            byte[] messageBytes = requestBody.asRaw().asBytes();
            byte[] encodedBytes = sha256_HMAC.doFinal(messageBytes);
            System.err.println("Encoded our bytes are : " + Hex.encodeHexString(encodedBytes));
            return signature.equals(Hex.encodeHexString(encodedBytes));
        } catch (NoSuchAlgorithmException e) {
            log.error("Cannot validate request. Invalid algorithm : {}",e);
            return false;
        } catch (InvalidKeyException e) {
            log.error("Cannot validate request. Invalid key : {}",e);
            return false;
        }

    }

    public static Result echoChallenge() {
        String requestArgs = request().getQueryString("challenge");
        if (requestArgs != null) {
            return ok(requestArgs);
        }
        else {
            return ok();
        }
    }

    @BodyParser.Of(BodyParser.Raw.class)
    public static Promise<Result> performWebHookTask() {
        if (!validateRequest()) {
            System.err.println("Validation of the request failed");
            Promise.pure(unauthorized());
        }
        Http.RequestBody requestBody = request().body();
        Http.RawBuffer rawRequest = requestBody.asRaw();
        File requestFile = rawRequest.asFile();
        ObjectMapper mapper = new ObjectMapper();
        BufferedReader fileReader = null;
        try {
           fileReader  = new BufferedReader(new FileReader(requestFile));
        } catch (FileNotFoundException e) {
            System.err.println("Unable to create a file from the raw request");
            log.error("Unable to create a file form the raw request body",e);
            return Promise.pure(internalServerError());
        }
        JsonNode rootNode;
        try {
            rootNode = mapper.readTree(fileReader);
        } catch (IOException e) {
            System.err.println("Unable to parse the JSON object");
            log.error("Unable to parse the JSON object",e);
            return Promise.pure(internalServerError());
        }
        Iterator<Map.Entry<String, JsonNode>> source = rootNode.fields();
        while (source.hasNext())
        {
            Map.Entry<String, JsonNode> item = source.next();
            if (item.getKey().equals("delta")) {
                JsonNode deltaNode = item.getValue();
                Iterator<Map.Entry<String, JsonNode>> userIterator = deltaNode.fields();
                while(userIterator.hasNext()) {
                    Map.Entry<String, JsonNode> userNode = userIterator.next();
                    if (userNode.getKey().equals("users")) {
                        JsonNode usersArrayNode = userNode.getValue();
                        if (usersArrayNode.isArray() ) {
                            for (final JsonNode objNode : usersArrayNode) {
                                processUser(objNode.asText());
                            }
                        }
                    }
                }
            }
        }
        return Promise.pure(ok());
    }

    public static Result done() {
        return ok(done.render());
    }

    private static Boolean processUser(String uid) {
        redisClient = pool.getResource();
        String oauthToken = redisClient.hget("tokens",uid);
        String userCursor = redisClient.hget("cursors",uid);

        DbxClient dropboxClient = new DbxClient(dropboxConfig,oauthToken);
        boolean hasMore = true;
        DbxDelta<DbxEntry> result = null;
        while (hasMore) {
            try {
                 result = dropboxClient.getDelta(userCursor);
            } catch (DbxException e) {
                log.error("Error getting the user detail for uid : {}, cursor : {}",uid,userCursor);
                play.Play.application().plugin(RedisPlugin.class).jedisPool().returnResource(redisClient);
                return false;
            }
            if (result != null) {
               for ( DbxDelta.Entry<DbxEntry> entry :  result.entries) {
                    if (entry.metadata == null || entry.metadata.isFolder() || !entry.lcPath.endsWith(".jpg")) {
                        continue;
                    }
                   File targetFile;
                   DbxEntry.File dropboxFile;
                   BufferedImage image = null;
                   try {
                       targetFile = File.createTempFile("dropbox_shrinker", "temp.jpg");
                   } catch (IOException e) {
                       log.error("Unable to create a temp file to acquire from dropbox",e);
                       play.Play.application().plugin(RedisPlugin.class).jedisPool().returnResource(redisClient);
                       return false;
                   }
                   String markDownContent;
                   try (OutputStream fileOutputStream = new FileOutputStream(targetFile)) {
                       dropboxFile = dropboxClient.getFile(entry.lcPath,null,fileOutputStream);
                       image = ImageIO.read(targetFile);
                   } catch (IOException e) {
                       log.error("Unable to create output stream for the file",e);
                       play.Play.application().plugin(RedisPlugin.class).jedisPool().returnResource(redisClient);
                       return false;
                   } catch (DbxException e) {
                       log.error("Unable to get file from dropbox",e);
                       play.Play.application().plugin(RedisPlugin.class).jedisPool().returnResource(redisClient);
                       return false;
                   }
                   image = Scalr.resize(image,500);
                   ByteArrayOutputStream os = new ByteArrayOutputStream();
                   try {
                       ImageIO.write(image, "jpg", os);
                   } catch (IOException e) {
                       log.error("Unable to convert markdown to html content",e);
                       play.Play.application().plugin(RedisPlugin.class).jedisPool().returnResource(redisClient);
                       return false;
                   }
                   InputStream inputStream1 = new ByteArrayInputStream(os.toByteArray());
                   if (image == null) {
                       log.error("Unable to scale down image");
                       play.Play.application().plugin(RedisPlugin.class).jedisPool().returnResource(redisClient);
                       return false;
                   }
                   String fileName = entry.metadata.path.substring(0,entry.metadata.path.length()-3);
                   fileName = fileName + "_small_" + ".jpg";
                   try {
                       DbxEntry.File uploadedFile = dropboxClient.uploadFile(fileName, DbxWriteMode.force(),
                               os.toByteArray().length, inputStream1);
                       System.out.println("uploaded file of size" + uploadedFile.humanSize);
                   } catch (IOException e) {
                       log.error("IO Exception while uploading file : {} ", e);
                       play.Play.application().plugin(RedisPlugin.class).jedisPool().returnResource(redisClient);
                       return false;
                   } catch (DbxException e) {
                       log.error("DbxException while uploading file : {} ", e);
                       play.Play.application().plugin(RedisPlugin.class).jedisPool().returnResource(redisClient);
                       return false;
                   }
               }
                userCursor = result.cursor;
                redisClient.hset("cursors",uid,userCursor);
                hasMore = result.hasMore;
            }

        }
        play.Play.application().plugin(RedisPlugin.class).jedisPool().returnResource(redisClient);
        return true;
    }

}
