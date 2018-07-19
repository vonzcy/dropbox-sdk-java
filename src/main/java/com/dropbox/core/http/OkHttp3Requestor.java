package com.dropbox.core.http;

import com.dropbox.core.util.IOUtil;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.io.Closeable;
import java.io.File;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okio.*;

/*>>> import checkers.nullness.quals.Nullable; */

/**
 * {@link HttpRequestor} implementation that uses <a href="http://square.github.io/okhttp/">OkHttp
 * v3</a>.  You can only use this if your project includes the OkHttp v3 library.
 */
public class OkHttp3Requestor extends HttpRequestor {
    /**
     * Returns an {@code OkHttpClient} instance with the default settings for this SDK.
     */
    public static OkHttpClient defaultOkHttpClient() {
        return defaultOkHttpClientBuilder().build();
    }

    /**
     * Returns an {@code OkHttpClient.Builder} instance with the default settings for this SDK.
     */
    public static OkHttpClient.Builder defaultOkHttpClientBuilder() {
        return new OkHttpClient.Builder()
            .connectTimeout(DEFAULT_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .readTimeout(DEFAULT_READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .writeTimeout(DEFAULT_READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            // enables certificate pinning
            .sslSocketFactory(SSLConfig.getSSLSocketFactory(), SSLConfig.getTrustManager());
    }

    private final OkHttpClient client;

    /**
     * Creates a new instance of this requestor that uses {@code client} for its requests.
     *
     * <pre>
     * OkHttpClient client = OkHttp3Requestor.defaultOkHttpClient();
     * HttpRequestor requestor = new OkHttp3Requestor(client);
     * </pre>
     *
     * <p>
     * If you need to make modifications to the {@link OkHttpClient} settings:
     * </p>
     *
     * <pre>
     * OkHttpClient client = OkHttp3Requestor.defaultOkHttpClientBuilder()
     *     .readTimeout(2, TimeUnit.MINUTES)
     *     ...
     *     .build();
     * </pre>
     *
     * <p>
     * If you don't use {@link #defaultOkHttpClient()} or {@link #defaultOkHttpClientBuilder()},
     * make sure to use Dropbox's hardened SSL settings from {@link SSLConfig}:
     * </p>
     *
     * <pre>
     * OkHttpClient client = OkHttpClient.Builder()
     *     ...
     *     .sslSocketFactory(SSLConfig.getSSLSocketFactory(), SSLConfig.getTrustManager())
     *     .build();
     * </pre>
     */
    public OkHttp3Requestor(OkHttpClient client) {
        if (client == null) throw new NullPointerException("client");
        OkHttpUtil.assertNotSameThreadExecutor(client.dispatcher().executorService());
        this.client = client;
    }

    /**
     * Returns the underlying {@code OkHttpClient} used to make requests.
     *
     * If you want to modify the client for a particular request, create a new instance of this
     * requestor with the modified client.
     *
     * @return underlying {@code OkHttpClient} used by this requestor.
     */
    public OkHttpClient getClient() {
        return client;
    }

    /**
     * Called beforing building the request and executing it.
     *
     * <p> This method should be used by subclasses to make any changes or additions to the request
     * before it is issued.
     *
     * @param request Builder of request to be executed
     */
    protected void configureRequest(Request.Builder request) { }

    /**
     * Called before returning {@link Response} from a request.
     *
     * <p> This method should be used by subclasses to add any logging, analytics, or cleanup
     * necessary.
     *
     * <p> If the response body is consumed, it should be replaced.
     *
     * @param response OkHttp response
     *
     * @return OkHttp response
     */
    protected okhttp3.Response interceptResponse(okhttp3.Response response) {
        return response;
    }

    @Override
    public Response doGet(String url, Iterable<Header> headers) throws IOException {
        Request.Builder builder = new Request.Builder().get().url(url);
        toOkHttpHeaders(headers, builder);
        configureRequest(builder);
        okhttp3.Response response = client.newCall(builder.build()).execute();
        response = interceptResponse(response);
        Map<String, List<String>> responseHeaders = fromOkHttpHeaders(response.headers());
        return new Response(response.code(), response.body().byteStream(), responseHeaders);
    }

    @Override
    public HttpRequestor.Uploader startPost(String url, Iterable<Header> headers) throws IOException {
        return startUpload(url, headers, "POST");
    }

    @Override
    public HttpRequestor.Uploader startPut(String url, Iterable<Header> headers) throws IOException {
        return startUpload(url, headers, "PUT");
    }

    private BufferedUploader startUpload(String url, Iterable<Header> headers, String method) {
        Request.Builder builder = new Request.Builder()
            .url(url);
        toOkHttpHeaders(headers, builder);
        return new BufferedUploader(method, builder);
    }

    private static void toOkHttpHeaders(Iterable<Header> headers, Request.Builder builder) {
        for (Header header : headers) {
            builder.addHeader(header.getKey(), header.getValue());
        }
    }

    private static Map<String, List<String>> fromOkHttpHeaders(Headers headers) {
        Map<String, List<String>> responseHeaders = new HashMap<String, List<String>>(headers.size());
        for (String name : headers.names()) {
            responseHeaders.put(name, headers.values(name));
        }
        return responseHeaders;
    }

    /**
     * OkHttp streaming upload interface is a bit awkard to use and expose.
     *
     * <p> OkHttp expects you to create a custom RequestBody class that produces the streaming
     * request body. The class must be defined and passed in before issuing the request.
     *
     * <p> To handle this, we try to avoid streaming if possible. If we must stream, then we create
     * a pipe and issue the request asynchronously in the background, attached to the pipe. We then
     * expose the other end of the pipe to the caller for streaming.
     */
    private class BufferedUploader extends HttpRequestor.Uploader {
        private final String method;
        private final Request.Builder request;

        private RequestBody body;
        private Call call;
        private AsyncCallback callback;

        private boolean closed;
        private boolean cancelled;

        public BufferedUploader(String method, Request.Builder request) {
            this.method = method;
            this.request = request;

            this.body = null;
            this.call = null;
            this.callback = null;

            this.closed = false;
            this.cancelled = false;
        }

        private void assertNoBody() {
            if (body != null) {
                throw new IllegalStateException("Request body already set.");
            }
        }

        @Override
        public OutputStream getBody() {
            // getBody() can be called multiple times to get access to the output stream. Don't
            // error if this is the case.
            if (body instanceof PipedRequestBody) {
                return ((PipedRequestBody) body).getOutputStream();
            } else {
                PipedRequestBody pipedBody = new CountingPipedRequstBody();
                setBody(pipedBody);

                this.callback = new AsyncCallback(pipedBody);
                this.call = client.newCall(request.build());
                // enqueue the call (async call execution). This allows us to provide streaming uploads.
                call.enqueue(callback);
                return pipedBody.getOutputStream();
            }
        }

        private void setBody(RequestBody body) {
            assertNoBody();
            this.body = body;
            this.request.method(method, body);
            configureRequest(request);
        }

        @Override
        public void upload(File file) {
            setBody(RequestBody.create(null, file));
        }

        @Override
        public void upload(byte [] body) {
            setBody(RequestBody.create(null, body));
        }

        @Override
        public void close() {
            if (body != null && (body instanceof Closeable)) {
                try {
                    ((Closeable) body).close();
                } catch (IOException ex) {
                    // ignore
                }
            }
            closed = true;
        }

        @Override
        public void abort() {
            if (call != null) {
                call.cancel();
            }
            cancelled = true;
            close();
        }

        @Override
        public Response finish() throws IOException {
            if (cancelled) {
                throw new IllegalStateException("Already aborted");
            }
            if (body == null) {
                upload(new byte[0]);
            }
            okhttp3.Response response;
            if (callback != null) {
                // ensure our request body is closed or we could deadlock
                try {
                    getBody().close();
                } catch (IOException ex) {
                    //ignore
                }
                response = callback.getResponse();
            } else {
                call = client.newCall(request.build());
                response = call.execute();
            }
            response = interceptResponse(response);
            Map<String, List<String>> responseHeaders = fromOkHttpHeaders(response.headers());
            return new Response(response.code(), response.body().byteStream(), responseHeaders);
        }
    }

    public static final class AsyncCallback implements Callback {
        private PipedRequestBody body;
        private IOException error;
        private okhttp3.Response response;

        private AsyncCallback(PipedRequestBody body) {
            this.body = body;
            this.error = null;
            this.response = null;
        }

        public synchronized okhttp3.Response getResponse() throws IOException {
            while (error == null && response == null) {
                try {
                    wait();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException();
                }
            }
            if (error != null) {
                throw error;
            }
            return response;
        }

        @Override
        public synchronized void onFailure(Call call, IOException ex) {
            this.error = ex;
            this.body.close();
            notifyAll();
        }

        @Override
        public synchronized void onResponse(Call call, okhttp3.Response response) throws IOException {
            this.response = response;
            notifyAll();
        }
    }

    private static class PipedRequestBody extends RequestBody implements Closeable {
        protected final OkHttpUtil.PipedStream stream;

        public PipedRequestBody() {
            this.stream = new OkHttpUtil.PipedStream();
        }

        public OutputStream getOutputStream() {
            return stream.getOutputStream();
        }

        @Override
        public void close() {
            stream.close();
        }

        @Override
        public MediaType contentType() {
            return null;
        }

        @Override
        public long contentLength() {
            return -1;
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            stream.writeTo(sink);
            close();
        }
    }

    private static class CountingPipedRequstBody extends PipedRequestBody {
        private IOUtil.ProgressListener listener;

        public CountingPipedRequstBody() {
            super();
        }

        public CountingPipedRequstBody(IOUtil.ProgressListener listener) {
            super();
            this.listener = listener;
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            CountingSink countingSink = new CountingSink(sink);
            BufferedSink bufferedSink = Okio.buffer(countingSink);
            super.stream.writeTo(bufferedSink);
            bufferedSink.flush();
        }

        private final class CountingSink extends ForwardingSink {

            private long bytesWritten = 0;

            public CountingSink(Sink delegate) {
                super(delegate);
            }

            @Override
            public void write(Buffer source, long byteCount) throws IOException {
                super.write(source, byteCount);

                bytesWritten += byteCount;

                if (listener != null) {
                    listener.onProgress(bytesWritten);
                }
            }
        }
    }
}
