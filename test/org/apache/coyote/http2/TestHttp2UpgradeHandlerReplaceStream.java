/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.coyote.http2;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Assert;
import org.junit.Test;

import org.apache.coyote.Request;

/*
 * Trailer fields received for an HTTP/2 stream that has been closed, replaced and recycled must never be written to the
 * (pooled) Request of that stream as that Request will be re-used by a subsequent stream.
 */
public class TestHttp2UpgradeHandlerReplaceStream {

    private static final String TRAILER_NAME = "x-trailer";
    private static final String TRAILER_VALUE = "leaked-from-stream-3";

    private static final long TIMEOUT_MILLIS = 10000;


    /*
     * Control test. Ensures the trailer block used in the other tests is delivered to an active stream so the "no leak"
     * assertions in those tests are meaningful.
     */
    @Test
    public void testTrailerDeliveredToActiveStream() throws Exception {
        TesterHttp2Protocol protocol = new TesterHttp2Protocol();
        Http2UpgradeHandler handler = new Http2UpgradeHandler(protocol, null, null);

        handler.headersStart(3, false);
        Stream stream = handler.getStream(3);
        Assert.assertNotNull(stream);
        handler.headersStart(3, true);

        handler.getHpackDecoder().decode(createTrailerBlock());

        Assert.assertEquals(TRAILER_VALUE, stream.getCoyoteRequest().getMimeTrailerFields().getHeader(TRAILER_NAME));
    }


    /*
     * The stream is closed and recycled after the start of the trailer headers but before the remaining trailer
     * headers (e.g. in CONTINUATION frames) have been decoded.
     */
    @Test
    public void testReplaceStreamClearsHeaderEmitter() throws Exception {
        TesterHttp2Protocol protocol = new TesterHttp2Protocol();
        Http2UpgradeHandler handler = new Http2UpgradeHandler(protocol, null, null);

        handler.headersStart(3, false);
        Stream stream = handler.getStream(3);
        Assert.assertNotNull(stream);
        Request request = stream.getCoyoteRequest();
        handler.headersStart(3, true);
        Assert.assertSame(stream, handler.getHpackDecoder().getHeaderEmitter());

        // Container thread completes processing of the stream
        closeAndRecycle(stream);

        Assert.assertNotSame(stream, handler.getHpackDecoder().getHeaderEmitter());
        Assert.assertSame(Http2UpgradeHandler.HEADER_SINK, handler.getHpackDecoder().getHeaderEmitter());

        // Remaining trailer headers arrive
        handler.getHpackDecoder().decode(createTrailerBlock());

        assertNoTrailerLeak(handler, request);
    }


    /*
     * The stream is closed and recycled on a container thread while the parser thread is processing the start of the
     * trailer headers (after the stream has been looked up but before it has been set as the header emitter).
     */
    @Test
    public void testReplaceStreamDuringHeadersStart() throws Exception {
        TesterHttp2Protocol protocol = new TesterHttp2Protocol();
        Http2UpgradeHandler handler = new Http2UpgradeHandler(protocol, null, null);

        handler.headersStart(3, false);
        Stream stream = handler.getStream(3);
        Assert.assertNotNull(stream);
        Request request = stream.getCoyoteRequest();

        ConcurrentStreamCloser closer = new ConcurrentStreamCloser(handler, stream);
        // Called from Stream.receivedStartOfHeaders() for trailer headers
        protocol.onGetMaxTrailerCount.set(closer);
        handler.headersStart(3, true);
        closer.awaitCompletion();

        Assert.assertNotSame(stream, handler.getHpackDecoder().getHeaderEmitter());

        // Trailer headers arrive
        handler.getHpackDecoder().decode(createTrailerBlock());

        assertNoTrailerLeak(handler, request);
    }


    /*
     * The stream is closed and recycled on a container thread while the parser thread is emitting a trailer header to
     * that stream.
     */
    @Test
    public void testReplaceStreamDuringEmitHeader() throws Exception {
        TesterHttp2Protocol protocol = new TesterHttp2Protocol();
        Http2UpgradeHandler handler = new Http2UpgradeHandler(protocol, null, null);

        handler.headersStart(3, false);
        Stream stream = handler.getStream(3);
        Assert.assertNotNull(stream);
        Request request = stream.getCoyoteRequest();
        handler.headersStart(3, true);

        ConcurrentStreamCloser closer = new ConcurrentStreamCloser(handler, stream);
        // Called from Stream.emitHeader() for trailer headers
        protocol.onIsTrailerHeaderAllowed.set(closer);
        handler.getHpackDecoder().decode(createTrailerBlock());
        closer.awaitCompletion();

        Assert.assertNotSame(stream, handler.getHpackDecoder().getHeaderEmitter());

        assertNoTrailerLeak(handler, request);
    }


    /*
     * Same sequence of calls as StreamProcessor once processing of a stream has completed.
     */
    private static void closeAndRecycle(Stream stream) {
        stream.replace();
        stream.recycle();
    }


    private static void assertNoTrailerLeak(Http2UpgradeHandler handler, Request request) throws Exception {
        // The next stream re-uses the pooled Request of the recycled stream
        handler.headersStart(5, false);
        Stream next = handler.getStream(5);
        Assert.assertNotNull(next);
        Assert.assertSame(request, next.getCoyoteRequest());

        Assert.assertNull(next.getCoyoteRequest().getMimeTrailerFields().getHeader(TRAILER_NAME));
        Assert.assertNull(next.getCoyoteRequest().getMimeHeaders().getHeader(TRAILER_NAME));
    }


    /*
     * HPACK: Literal Header Field without Indexing - New Name (RFC 7541, section 6.2.2) without Huffman encoding.
     */
    private static ByteBuffer createTrailerBlock() {
        byte[] name = TRAILER_NAME.getBytes(StandardCharsets.ISO_8859_1);
        byte[] value = TRAILER_VALUE.getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer buffer = ByteBuffer.allocate(3 + name.length + value.length);
        buffer.put((byte) 0x00);
        buffer.put((byte) name.length);
        buffer.put(name);
        buffer.put((byte) value.length);
        buffer.put(value);
        buffer.flip();
        return buffer;
    }


    /*
     * Simulates a container thread completing processing of the stream (and hence replacing and recycling it)
     * concurrently with the parser thread. When run, it starts the container thread and then waits until that thread
     * has either completed or is blocked waiting for the parser thread.
     */
    private static class ConcurrentStreamCloser implements Runnable {

        private final Http2UpgradeHandler handler;
        private final Stream stream;
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private volatile Thread thread;

        ConcurrentStreamCloser(Http2UpgradeHandler handler, Stream stream) {
            this.handler = handler;
            this.stream = stream;
        }

        @Override
        public void run() {
            thread = new Thread(() -> {
                try {
                    closeAndRecycle(stream);
                } catch (Throwable t) {
                    error.set(t);
                }
            }, "ConcurrentStreamCloser");
            thread.setDaemon(true);
            thread.start();

            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS);
            while (thread.isAlive() && !handler.getHpackDecoder().getStreamEmitterLock().hasQueuedThread(thread)) {
                if (System.nanoTime() > deadline) {
                    Assert.fail("Timed out waiting for the stream to be closed");
                }
                Thread.yield();
            }
        }

        void awaitCompletion() throws Exception {
            Assert.assertNotNull("Stream was not closed concurrently", thread);
            thread.join(TIMEOUT_MILLIS);
            Assert.assertFalse(thread.isAlive());
            if (error.get() != null) {
                throw new AssertionError(error.get());
            }
        }
    }


    private static class TesterHttp2Protocol extends Http2Protocol {

        private final AtomicReference<Runnable> onGetMaxTrailerCount = new AtomicReference<>();
        private final AtomicReference<Runnable> onIsTrailerHeaderAllowed = new AtomicReference<>();

        TesterHttp2Protocol() {
            setAllowedTrailerHeaders(TRAILER_NAME);
        }

        @Override
        public int getMaxTrailerCount() {
            runOnce(onGetMaxTrailerCount);
            return super.getMaxTrailerCount();
        }

        @Override
        boolean isTrailerHeaderAllowed(String headerName) {
            runOnce(onIsTrailerHeaderAllowed);
            return super.isTrailerHeaderAllowed(headerName);
        }

        private static void runOnce(AtomicReference<Runnable> hook) {
            Runnable r = hook.getAndSet(null);
            if (r != null) {
                r.run();
            }
        }
    }
}
