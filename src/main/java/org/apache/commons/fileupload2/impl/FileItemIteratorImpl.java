/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.fileupload2.impl;

import static java.lang.String.format;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

import org.apache.commons.fileupload2.FileItem;
import org.apache.commons.fileupload2.FileItemHeaders;
import org.apache.commons.fileupload2.FileItemIterator;
import org.apache.commons.fileupload2.FileItemStream;
import org.apache.commons.fileupload2.FileUploadBase;
import org.apache.commons.fileupload2.FileUploadException;
import org.apache.commons.fileupload2.MultipartStream;
import org.apache.commons.fileupload2.ProgressListener;
import org.apache.commons.fileupload2.RequestContext;
import org.apache.commons.fileupload2.UploadContext;
import org.apache.commons.fileupload2.util.LimitedInputStream;
import org.apache.commons.io.IOUtils;

/**
 * The iterator, which is returned by
 * {@link FileUploadBase#getItemIterator(RequestContext)}.
 */
public class FileItemIteratorImpl implements FileItemIterator {
	private final FileUploadBase fileUploadBase;
	private final RequestContext ctx;
	private long sizeMax, fileSizeMax;


	@Override
	public long getSizeMax() {
		return sizeMax;
	}

	@Override
	public void setSizeMax(long sizeMax) {
		this.sizeMax = sizeMax;
	}

	@Override
	public long getFileSizeMax() {
		return fileSizeMax;
	}

	@Override
	public void setFileSizeMax(long fileSizeMax) {
		this.fileSizeMax = fileSizeMax;
	}

	/**
     * The multi part stream to process.
     */
    private MultipartStream multiPartStream;

    /**
     * The notifier, which used for triggering the
     * {@link ProgressListener}.
     */
    private MultipartStream.ProgressNotifier progressNotifier;

    /**
     * The boundary, which separates the various parts.
     */
    private byte[] multiPartBoundary;

    /**
     * The item, which we currently process.
     */
    private FileItemStreamImpl currentItem;

    /**
     * The current items field name.
     */
    private String currentFieldName;

    /**
     * Whether we are currently skipping the preamble.
     */
    private boolean skipPreamble;

    /**
     * Whether the current item may still be read.
     */
    private boolean itemValid;

    /**
     * Whether we have seen the end of the file.
     */
    private boolean eof;

    /**
     * Creates a new instance.
     *
     * @param ctx The request context.
     * @throws FileUploadException An error occurred while
     *   parsing the request.
     * @throws IOException An I/O error occurred.
     */
    public FileItemIteratorImpl(FileUploadBase pFileUploadBase, RequestContext pRequestContext)
            throws FileUploadException, IOException {
    	fileUploadBase = pFileUploadBase;
    	sizeMax = fileUploadBase.getSizeMax();
    	fileSizeMax = fileUploadBase.getFileSizeMax();
    	ctx = pRequestContext;
        if (ctx == null) {
            throw new NullPointerException("ctx parameter");
        }


        skipPreamble = true;
        findNextItem();
    }

    protected void init(FileUploadBase fileUploadBase, RequestContext pRequestContext)
            throws FileUploadException, IOException {
        String contentType = ctx.getContentType();
        if ((null == contentType)
                || (!contentType.toLowerCase(Locale.ENGLISH).startsWith(FileUploadBase.MULTIPART))) {
            throw new InvalidContentTypeException(
                    format("the request doesn't contain a %s or %s stream, content type header is %s",
                           FileUploadBase.MULTIPART_FORM_DATA, FileUploadBase.MULTIPART_MIXED, contentType));
        }


        @SuppressWarnings("deprecation") // still has to be backward compatible
        final int contentLengthInt = ctx.getContentLength();

        final long requestSize = UploadContext.class.isAssignableFrom(ctx.getClass())
                                 // Inline conditional is OK here CHECKSTYLE:OFF
                                 ? ((UploadContext) ctx).contentLength()
                                 : contentLengthInt;
                                 // CHECKSTYLE:ON

        InputStream input; // N.B. this is eventually closed in MultipartStream processing
        if (sizeMax >= 0) {
            if (requestSize != -1 && requestSize > sizeMax) {
                throw new SizeLimitExceededException(
                    format("the request was rejected because its size (%s) exceeds the configured maximum (%s)",
                            Long.valueOf(requestSize), Long.valueOf(sizeMax)),
                           requestSize, sizeMax);
            }
            // N.B. this is eventually closed in MultipartStream processing
            input = new LimitedInputStream(ctx.getInputStream(), sizeMax) {
                @Override
                protected void raiseError(long pSizeMax, long pCount)
                        throws IOException {
                    FileUploadException ex = new SizeLimitExceededException(
                    format("the request was rejected because its size (%s) exceeds the configured maximum (%s)",
                            Long.valueOf(pCount), Long.valueOf(pSizeMax)),
                           pCount, pSizeMax);
                    throw new FileUploadIOException(ex);
                }
            };
        } else {
            input = ctx.getInputStream();
        }

        String charEncoding = fileUploadBase.getHeaderEncoding();
        if (charEncoding == null) {
            charEncoding = ctx.getCharacterEncoding();
        }

        multiPartBoundary = fileUploadBase.getBoundary(contentType);
        if (multiPartBoundary == null) {
            IOUtils.closeQuietly(input); // avoid possible resource leak
            throw new FileUploadException("the request was rejected because no multipart boundary was found");
        }

        progressNotifier = new MultipartStream.ProgressNotifier(fileUploadBase.getProgressListener(), requestSize);
        try {
            multiPartStream = new MultipartStream(input, multiPartBoundary, progressNotifier);
        } catch (IllegalArgumentException iae) {
            IOUtils.closeQuietly(input); // avoid possible resource leak
            throw new InvalidContentTypeException(
                    format("The boundary specified in the %s header is too long", FileUploadBase.CONTENT_TYPE), iae);
        }
        multiPartStream.setHeaderEncoding(charEncoding);
    }

    public MultipartStream getMultiPartStream() throws FileUploadException, IOException {
    	if (multiPartStream == null) {
    		init(fileUploadBase, ctx);
    	}
    	return multiPartStream;
    }

    /**
     * Called for finding the next item, if any.
     *
     * @return True, if an next item was found, otherwise false.
     * @throws IOException An I/O error occurred.
     */
    private boolean findNextItem() throws FileUploadException, IOException {
        if (eof) {
            return false;
        }
        if (currentItem != null) {
            currentItem.close();
            currentItem = null;
        }
        final MultipartStream multi = getMultiPartStream();
        for (;;) {
            boolean nextPart;
            if (skipPreamble) {
                nextPart = multi.skipPreamble();
            } else {
                nextPart = multi.readBoundary();
            }
            if (!nextPart) {
                if (currentFieldName == null) {
                    // Outer multipart terminated -> No more data
                    eof = true;
                    return false;
                }
                // Inner multipart terminated -> Return to parsing the outer
                multi.setBoundary(multiPartBoundary);
                currentFieldName = null;
                continue;
            }
            FileItemHeaders headers = fileUploadBase.getParsedHeaders(multi.readHeaders());
            if (currentFieldName == null) {
                // We're parsing the outer multipart
                String fieldName = fileUploadBase.getFieldName(headers);
                if (fieldName != null) {
                    String subContentType = headers.getHeader(FileUploadBase.CONTENT_TYPE);
                    if (subContentType != null
                            &&  subContentType.toLowerCase(Locale.ENGLISH)
                                    .startsWith(FileUploadBase.MULTIPART_MIXED)) {
                        currentFieldName = fieldName;
                        // Multiple files associated with this field name
                        byte[] subBoundary = fileUploadBase.getBoundary(subContentType);
                        multi.setBoundary(subBoundary);
                        skipPreamble = true;
                        continue;
                    }
                    String fileName = fileUploadBase.getFileName(headers);
                    currentItem = new FileItemStreamImpl(this, fileName,
                            fieldName, headers.getHeader(FileUploadBase.CONTENT_TYPE),
                            fileName == null, getContentLength(headers));
                    currentItem.setHeaders(headers);
                    progressNotifier.noteItem();
                    itemValid = true;
                    return true;
                }
            } else {
                String fileName = fileUploadBase.getFileName(headers);
                if (fileName != null) {
                    currentItem = new FileItemStreamImpl(this, fileName,
                            currentFieldName,
                            headers.getHeader(FileUploadBase.CONTENT_TYPE),
                            false, getContentLength(headers));
                    currentItem.setHeaders(headers);
                    progressNotifier.noteItem();
                    itemValid = true;
                    return true;
                }
            }
            multi.discardBodyData();
        }
    }

    private long getContentLength(FileItemHeaders pHeaders) {
        try {
            return Long.parseLong(pHeaders.getHeader(FileUploadBase.CONTENT_LENGTH));
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Returns, whether another instance of {@link FileItemStream}
     * is available.
     *
     * @throws FileUploadException Parsing or processing the
     *   file item failed.
     * @throws IOException Reading the file item failed.
     * @return True, if one or more additional file items
     *   are available, otherwise false.
     */
    @Override
    public boolean hasNext() throws FileUploadException, IOException {
        if (eof) {
            return false;
        }
        if (itemValid) {
            return true;
        }
        try {
            return findNextItem();
        } catch (FileUploadIOException e) {
            // unwrap encapsulated SizeException
            throw (FileUploadException) e.getCause();
        }
    }

    /**
     * Returns the next available {@link FileItemStream}.
     *
     * @throws java.util.NoSuchElementException No more items are
     *   available. Use {@link #hasNext()} to prevent this exception.
     * @throws FileUploadException Parsing or processing the
     *   file item failed.
     * @throws IOException Reading the file item failed.
     * @return FileItemStream instance, which provides
     *   access to the next file item.
     */
    @Override
    public FileItemStream next() throws FileUploadException, IOException {
        if (eof  ||  (!itemValid && !hasNext())) {
            throw new NoSuchElementException();
        }
        itemValid = false;
        return currentItem;
    }

	@Override
	public List<FileItem> getFileItems() throws FileUploadException, IOException {
		final List<FileItem> items = new ArrayList<FileItem>();
		while (hasNext()) {
			final FileItemStream fis = next();
			final FileItem fi = fileUploadBase.getFileItemFactory().createItem(fis.getFieldName(), fis.getContentType(), fis.isFormField(), fis.getName());
			items.add(fi);
		}
		return items;
	}

}