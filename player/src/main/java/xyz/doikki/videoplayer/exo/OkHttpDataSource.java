package xyz.doikki.videoplayer.exo;

import static com.google.android.exoplayer2.upstream.HttpUtil.buildRangeRequestHeader;
import static java.lang.Math.min;

import android.net.Uri;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.upstream.BaseDataSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSourceException;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.upstream.HttpDataSource.HttpDataSourceException;
import com.google.android.exoplayer2.upstream.HttpDataSource.InvalidContentTypeException;
import com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException;
import com.google.android.exoplayer2.upstream.HttpUtil;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Util;
import com.google.common.base.Predicate;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * ExoPlayer HTTP data source backed by okhttp3.
 */
@Deprecated
public final class OkHttpDataSource extends BaseDataSource implements HttpDataSource {

    public static final class Factory extends HttpDataSource.BaseFactory {

        private final Call.Factory callFactory;
        @Nullable
        private String userAgent;
        @Nullable
        private TransferListener transferListener;
        @Nullable
        private CacheControl cacheControl;
        @Nullable
        private Predicate<String> contentTypePredicate;

        public Factory(Call.Factory callFactory) {
            this.callFactory = callFactory;
        }

        public Factory setUserAgent(@Nullable String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public Factory setCacheControl(@Nullable CacheControl cacheControl) {
            this.cacheControl = cacheControl;
            return this;
        }

        public Factory setContentTypePredicate(@Nullable Predicate<String> contentTypePredicate) {
            this.contentTypePredicate = contentTypePredicate;
            return this;
        }

        public Factory setTransferListener(@Nullable TransferListener transferListener) {
            this.transferListener = transferListener;
            return this;
        }

        @Override
        protected HttpDataSource createDataSourceInternal(RequestProperties defaultRequestProperties) {
            OkHttpDataSource dataSource = new OkHttpDataSource(callFactory, userAgent, cacheControl, defaultRequestProperties, contentTypePredicate);
            if (transferListener != null) {
                dataSource.addTransferListener(transferListener);
            }
            return dataSource;
        }
    }

    private final Call.Factory callFactory;
    private final RequestProperties requestProperties;
    @Nullable
    private final String userAgent;
    @Nullable
    private final CacheControl cacheControl;
    @Nullable
    private final RequestProperties defaultRequestProperties;
    @Nullable
    private Predicate<String> contentTypePredicate;
    @Nullable
    private DataSpec dataSpec;
    @Nullable
    private Response response;
    @Nullable
    private InputStream responseByteStream;
    private boolean opened;
    private long bytesToRead;
    private long bytesRead;

    public OkHttpDataSource(Call.Factory callFactory) {
        this(callFactory, null);
    }

    public OkHttpDataSource(Call.Factory callFactory, @Nullable String userAgent) {
        this(callFactory, userAgent, null, null, null);
    }

    public OkHttpDataSource(Call.Factory callFactory, @Nullable String userAgent, @Nullable CacheControl cacheControl, @Nullable HttpDataSource.RequestProperties defaultRequestProperties) {
        this(callFactory, userAgent, cacheControl, defaultRequestProperties, null);
    }

    private OkHttpDataSource(Call.Factory callFactory, @Nullable String userAgent, @Nullable CacheControl cacheControl, @Nullable HttpDataSource.RequestProperties defaultRequestProperties, @Nullable Predicate<String> contentTypePredicate) {
        super(true);
        this.callFactory = Assertions.checkNotNull(callFactory);
        this.userAgent = userAgent;
        this.cacheControl = cacheControl;
        this.defaultRequestProperties = defaultRequestProperties;
        this.contentTypePredicate = contentTypePredicate;
        this.requestProperties = new RequestProperties();
    }

    @Deprecated
    public void setContentTypePredicate(@Nullable Predicate<String> contentTypePredicate) {
        this.contentTypePredicate = contentTypePredicate;
    }

    @Override
    public @Nullable Uri getUri() {
        return response == null ? null : Uri.parse(response.request().url().toString());
    }

    @Override
    public int getResponseCode() {
        return response == null ? -1 : response.code();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return response == null ? Collections.<String, List<String>>emptyMap() : response.headers().toMultimap();
    }

    @Override
    public void setRequestProperty(String name, String value) {
        Assertions.checkNotNull(name);
        Assertions.checkNotNull(value);
        requestProperties.set(name, value);
    }

    @Override
    public void clearRequestProperty(String name) {
        Assertions.checkNotNull(name);
        requestProperties.remove(name);
    }

    @Override
    public void clearAllRequestProperties() {
        requestProperties.clear();
    }

    @Override
    public long open(DataSpec dataSpec) throws HttpDataSourceException {
        this.dataSpec = dataSpec;
        bytesRead = 0;
        bytesToRead = 0;
        transferInitializing(dataSpec);

        Request request = makeRequest(dataSpec);
        Response response;
        ResponseBody responseBody;
        Call call = callFactory.newCall(request);
        try {
            response = executeCall(call);
            this.response = response;
            responseBody = Assertions.checkNotNull(response.body());
            responseByteStream = responseBody.byteStream();
        } catch (IOException e) {
            throw HttpDataSourceException.createForIOException(e, dataSpec, HttpDataSourceException.TYPE_OPEN);
        }

        int responseCode = response.code();
        if (!response.isSuccessful()) {
            if (responseCode == 416) {
                long documentSize = HttpUtil.getDocumentSize(response.headers().get("Content-Range"));
                if (dataSpec.position == documentSize) {
                    opened = true;
                    transferStarted(dataSpec);
                    return dataSpec.length != C.LENGTH_UNSET ? dataSpec.length : 0;
                }
            }

            byte[] errorResponseBody;
            try {
                errorResponseBody = Util.toByteArray(Assertions.checkNotNull(responseByteStream));
            } catch (IOException e) {
                errorResponseBody = Util.EMPTY_BYTE_ARRAY;
            }
            Map<String, List<String>> headers = response.headers().toMultimap();
            closeConnectionQuietly();
            IOException cause = responseCode == 416 ? new DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) : null;
            throw new InvalidResponseCodeException(responseCode, response.message(), cause, headers, dataSpec, errorResponseBody);
        }

        MediaType mediaType = responseBody.contentType();
        String contentType = mediaType != null ? mediaType.toString() : "";
        if (contentTypePredicate != null && !contentTypePredicate.apply(contentType)) {
            closeConnectionQuietly();
            throw new InvalidContentTypeException(contentType, dataSpec);
        }

        long bytesToSkip = responseCode == 200 && dataSpec.position != 0 ? dataSpec.position : 0;
        if (dataSpec.length != C.LENGTH_UNSET) {
            bytesToRead = dataSpec.length;
        } else {
            long contentLength = responseBody.contentLength();
            bytesToRead = contentLength != -1 ? (contentLength - bytesToSkip) : C.LENGTH_UNSET;
        }

        opened = true;
        transferStarted(dataSpec);

        try {
            skipFully(bytesToSkip, dataSpec);
        } catch (HttpDataSourceException e) {
            closeConnectionQuietly();
            throw e;
        }
        return bytesToRead;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws HttpDataSourceException {
        try {
            return readInternal(buffer, offset, length);
        } catch (IOException e) {
            throw HttpDataSourceException.createForIOException(e, Assertions.checkNotNull(dataSpec), HttpDataSourceException.TYPE_READ);
        }
    }

    @Override
    public void close() throws HttpDataSourceException {
        if (opened) {
            opened = false;
            transferEnded();
            closeConnectionQuietly();
        }
    }

    private Request makeRequest(DataSpec dataSpec) throws HttpDataSourceException {
        long position = dataSpec.position;
        long length = dataSpec.length;

        HttpUrl url = HttpUrl.parse(dataSpec.uri.toString());
        if (url == null) {
            throw new HttpDataSourceException("Malformed URL", dataSpec, PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK, HttpDataSourceException.TYPE_OPEN);
        }

        Request.Builder builder = new Request.Builder().url(url);
        if (cacheControl != null) {
            builder.cacheControl(cacheControl);
        }

        Map<String, String> headers = new HashMap<>();
        if (defaultRequestProperties != null) {
            headers.putAll(defaultRequestProperties.getSnapshot());
        }
        headers.putAll(requestProperties.getSnapshot());
        headers.putAll(dataSpec.httpRequestHeaders);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }

        String rangeHeader = buildRangeRequestHeader(position, length);
        if (rangeHeader != null) {
            builder.addHeader("Range", rangeHeader);
        }
        if (userAgent != null) {
            builder.addHeader("User-Agent", userAgent);
        }
        if (!dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP)) {
            builder.addHeader("Accept-Encoding", "identity");
        }

        RequestBody requestBody = null;
        if (dataSpec.httpBody != null) {
            requestBody = RequestBody.create(null, dataSpec.httpBody);
        } else if (dataSpec.httpMethod == DataSpec.HTTP_METHOD_POST) {
            requestBody = RequestBody.create(null, Util.EMPTY_BYTE_ARRAY);
        }
        builder.method(dataSpec.getHttpMethodString(), requestBody);
        return builder.build();
    }

    private Response executeCall(Call call) throws IOException {
        final Response[] responseHolder = new Response[1];
        final IOException[] errorHolder = new IOException[1];
        final CountDownLatch latch = new CountDownLatch(1);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                errorHolder[0] = e;
                latch.countDown();
            }

            @Override
            public void onResponse(Call call, Response response) {
                responseHolder[0] = response;
                latch.countDown();
            }
        });

        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
                call.cancel();
            }
        }
        if (interrupted) {
            throw new InterruptedIOException();
        }
        if (errorHolder[0] != null) {
            throw errorHolder[0];
        }
        return Assertions.checkNotNull(responseHolder[0]);
    }

    private void skipFully(long bytesToSkip, DataSpec dataSpec) throws HttpDataSourceException {
        if (bytesToSkip == 0) {
            return;
        }
        byte[] skipBuffer = new byte[4096];
        try {
            while (bytesToSkip > 0) {
                int readLength = (int) min(bytesToSkip, skipBuffer.length);
                int read = Assertions.checkNotNull(responseByteStream).read(skipBuffer, 0, readLength);
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException();
                }
                if (read == -1) {
                    throw new HttpDataSourceException(dataSpec, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, HttpDataSourceException.TYPE_OPEN);
                }
                bytesToSkip -= read;
                bytesTransferred(read);
            }
        } catch (IOException e) {
            if (e instanceof HttpDataSourceException) {
                throw (HttpDataSourceException) e;
            }
            throw new HttpDataSourceException(dataSpec, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, HttpDataSourceException.TYPE_OPEN);
        }
    }

    private int readInternal(byte[] buffer, int offset, int readLength) throws IOException {
        if (readLength == 0) {
            return 0;
        }
        if (bytesToRead != C.LENGTH_UNSET) {
            long bytesRemaining = bytesToRead - bytesRead;
            if (bytesRemaining == 0) {
                return C.RESULT_END_OF_INPUT;
            }
            readLength = (int) min(readLength, bytesRemaining);
        }

        int read = Assertions.checkNotNull(responseByteStream).read(buffer, offset, readLength);
        if (read == -1) {
            return C.RESULT_END_OF_INPUT;
        }

        bytesRead += read;
        bytesTransferred(read);
        return read;
    }

    private void closeConnectionQuietly() {
        if (response != null) {
            ResponseBody body = response.body();
            if (body != null) {
                body.close();
            }
            response = null;
        }
        responseByteStream = null;
    }
}
