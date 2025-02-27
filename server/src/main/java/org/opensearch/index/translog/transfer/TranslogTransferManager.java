/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.translog.transfer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.lucene.store.IndexInput;
import org.opensearch.action.ActionListener;
import org.opensearch.action.LatchedActionListener;
import org.opensearch.common.blobstore.BlobPath;
import org.opensearch.common.lucene.store.ByteArrayIndexInput;
import org.opensearch.index.translog.Translog;
import org.opensearch.index.translog.transfer.listener.TranslogTransferListener;
import org.opensearch.threadpool.ThreadPool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.opensearch.index.translog.transfer.FileSnapshot.TransferFileSnapshot;
import static org.opensearch.index.translog.transfer.FileSnapshot.TranslogFileSnapshot;

/**
 * The class responsible for orchestrating the transfer of a {@link TransferSnapshot} via a {@link TransferService}
 *
 * @opensearch.internal
 */
public class TranslogTransferManager {

    private final TransferService transferService;
    private final BlobPath remoteBaseTransferPath;
    private final BlobPath remoteMetadataTransferPath;
    private final FileTransferTracker fileTransferTracker;

    private static final long TRANSFER_TIMEOUT_IN_MILLIS = 30000;

    private static final Logger logger = LogManager.getLogger(TranslogTransferManager.class);

    private final static String METADATA_DIR = "metadata";

    public TranslogTransferManager(
        TransferService transferService,
        BlobPath remoteBaseTransferPath,
        FileTransferTracker fileTransferTracker
    ) {
        this.transferService = transferService;
        this.remoteBaseTransferPath = remoteBaseTransferPath;
        this.remoteMetadataTransferPath = remoteBaseTransferPath.add(METADATA_DIR);
        this.fileTransferTracker = fileTransferTracker;
    }

    public boolean transferSnapshot(TransferSnapshot transferSnapshot, TranslogTransferListener translogTransferListener)
        throws IOException {
        List<Exception> exceptionList = new ArrayList<>(transferSnapshot.getTranslogTransferMetadata().getCount());
        Set<TransferFileSnapshot> toUpload = new HashSet<>(transferSnapshot.getTranslogTransferMetadata().getCount());
        try {
            toUpload.addAll(fileTransferTracker.exclusionFilter(transferSnapshot.getTranslogFileSnapshots()));
            toUpload.addAll(fileTransferTracker.exclusionFilter((transferSnapshot.getCheckpointFileSnapshots())));
            if (toUpload.isEmpty()) {
                logger.trace("Nothing to upload for transfer");
                translogTransferListener.onUploadComplete(transferSnapshot);
                return true;
            }
            final CountDownLatch latch = new CountDownLatch(toUpload.size());
            LatchedActionListener<TransferFileSnapshot> latchedActionListener = new LatchedActionListener<>(
                ActionListener.wrap(fileTransferTracker::onSuccess, ex -> {
                    assert ex instanceof FileTransferException;
                    logger.error(
                        () -> new ParameterizedMessage(
                            "Exception during transfer for file {}",
                            ((FileTransferException) ex).getFileSnapshot().getName()
                        ),
                        ex
                    );
                    FileTransferException e = (FileTransferException) ex;
                    fileTransferTracker.onFailure(e.getFileSnapshot(), ex);
                    exceptionList.add(ex);
                }),
                latch
            );
            toUpload.forEach(
                fileSnapshot -> transferService.uploadBlobAsync(
                    ThreadPool.Names.TRANSLOG_TRANSFER,
                    fileSnapshot,
                    remoteBaseTransferPath.add(String.valueOf(fileSnapshot.getPrimaryTerm())),
                    latchedActionListener
                )
            );
            try {
                if (latch.await(TRANSFER_TIMEOUT_IN_MILLIS, TimeUnit.MILLISECONDS) == false) {
                    Exception ex = new TimeoutException("Timed out waiting for transfer of snapshot " + transferSnapshot + " to complete");
                    exceptionList.forEach(ex::addSuppressed);
                    throw ex;
                }
            } catch (InterruptedException ex) {
                exceptionList.forEach(ex::addSuppressed);
                Thread.currentThread().interrupt();
                throw ex;
            }
            if (exceptionList.isEmpty()) {
                transferService.uploadBlob(prepareMetadata(transferSnapshot), remoteMetadataTransferPath);
                translogTransferListener.onUploadComplete(transferSnapshot);
                return true;
            } else {
                Exception ex = new IOException("Failed to upload " + exceptionList.size() + " files during transfer");
                exceptionList.forEach(ex::addSuppressed);
                throw ex;
            }
        } catch (Exception ex) {
            logger.error(() -> new ParameterizedMessage("Transfer failed for snapshot {}", transferSnapshot), ex);
            translogTransferListener.onUploadFailed(transferSnapshot, ex);
            return false;
        }
    }

    public boolean downloadTranslog(String primaryTerm, String generation, Path location) throws IOException {
        logger.info(
            "Downloading translog files with: Primary Term = {}, Generation = {}, Location = {}",
            primaryTerm,
            generation,
            location
        );
        // Download Checkpoint file from remote to local FS
        String ckpFileName = Translog.getCommitCheckpointFileName(Long.parseLong(generation));
        downloadToFS(ckpFileName, location, primaryTerm);
        // Download translog file from remote to local FS
        String translogFilename = Translog.getFilename(Long.parseLong(generation));
        downloadToFS(translogFilename, location, primaryTerm);
        return true;
    }

    private void downloadToFS(String fileName, Path location, String primaryTerm) throws IOException {
        Path filePath = location.resolve(fileName);
        // Here, we always override the existing file if present.
        // We need to change this logic when we introduce incremental download
        if (Files.exists(filePath)) {
            Files.delete(filePath);
        }
        try (InputStream inputStream = transferService.downloadBlob(remoteBaseTransferPath.add(primaryTerm), fileName)) {
            Files.copy(inputStream, filePath);
        }
        // Mark in FileTransferTracker so that the same files are not uploaded at the time of translog sync
        fileTransferTracker.add(fileName, true);
    }

    public TranslogTransferMetadata readMetadata() throws IOException {
        return transferService.listAll(remoteMetadataTransferPath)
            .stream()
            .max(TranslogTransferMetadata.METADATA_FILENAME_COMPARATOR)
            .map(filename -> {
                try (InputStream inputStream = transferService.downloadBlob(remoteMetadataTransferPath, filename);) {
                    IndexInput indexInput = new ByteArrayIndexInput("metadata file", inputStream.readAllBytes());
                    return new TranslogTransferMetadata(indexInput);
                } catch (IOException e) {
                    logger.error(() -> new ParameterizedMessage("Exception while reading metadata file: {}", filename), e);
                    return null;
                }
            })
            .orElse(null);
    }

    private TransferFileSnapshot prepareMetadata(TransferSnapshot transferSnapshot) throws IOException {
        Map<String, String> generationPrimaryTermMap = transferSnapshot.getTranslogFileSnapshots().stream().map(s -> {
            assert s instanceof TranslogFileSnapshot;
            return (TranslogFileSnapshot) s;
        })
            .collect(
                Collectors.toMap(
                    snapshot -> String.valueOf(snapshot.getGeneration()),
                    snapshot -> String.valueOf(snapshot.getPrimaryTerm())
                )
            );
        TranslogTransferMetadata translogTransferMetadata = transferSnapshot.getTranslogTransferMetadata();
        translogTransferMetadata.setGenerationToPrimaryTermMapper(new HashMap<>(generationPrimaryTermMap));
        return new TransferFileSnapshot(
            translogTransferMetadata.getFileName(),
            translogTransferMetadata.createMetadataBytes(),
            translogTransferMetadata.getPrimaryTerm()
        );
    }

    /**
     * This method handles deletion of multiple generations for a single primary term.
     * TODO: Take care of metadata file cleanup. <a href="https://github.com/opensearch-project/OpenSearch/issues/5677">Github Issue #5677</a>
     *
     * @param primaryTerm primary term where the generations will be deleted.
     * @param generations set of generation to delete.
     */
    public void deleteTranslogAsync(long primaryTerm, Set<Long> generations) {
        if (generations.isEmpty()) {
            return;
        }
        List<String> files = new ArrayList<>();
        generations.forEach(generation -> {
            String ckpFileName = Translog.getCommitCheckpointFileName(generation);
            String translogFilename = Translog.getFilename(generation);
            files.addAll(List.of(ckpFileName, translogFilename));
        });
        transferService.deleteBlobsAsync(
            ThreadPool.Names.REMOTE_PURGE,
            remoteBaseTransferPath.add(String.valueOf(primaryTerm)),
            files,
            new ActionListener<>() {
                @Override
                public void onResponse(Void unused) {
                    fileTransferTracker.delete(files);
                    logger.trace("Deleted translogs for primaryTerm {} generations {}", primaryTerm, generations);
                }

                @Override
                public void onFailure(Exception e) {
                    logger.error(
                        () -> new ParameterizedMessage(
                            "Exception occurred while deleting translog for primary_term={} generations={}",
                            primaryTerm,
                            generations
                        ),
                        e
                    );
                }
            }
        );
    }

    /**
     * Deletes all primary terms from remote store that are more than the given {@code minPrimaryTermToKeep}. The caller
     * of the method must ensure that the value is lesser than equal to the minimum primary term referenced by the remote
     * translog metadata.
     *
     * @param minPrimaryTermToKeep all primary terms below this primary term are deleted.
     */
    public void deletePrimaryTermsAsync(long minPrimaryTermToKeep) {
        logger.info("Deleting primary terms from remote store lesser than {}", minPrimaryTermToKeep);
        transferService.listFoldersAsync(ThreadPool.Names.REMOTE_PURGE, remoteBaseTransferPath, new ActionListener<>() {
            @Override
            public void onResponse(Set<String> folders) {
                Set<Long> primaryTermsInRemote = folders.stream().filter(folderName -> {
                    try {
                        Long.parseLong(folderName);
                        return true;
                    } catch (Exception ignored) {
                        // NO-OP
                    }
                    return false;
                }).map(Long::parseLong).collect(Collectors.toSet());
                Set<Long> primaryTermsToDelete = primaryTermsInRemote.stream()
                    .filter(term -> term < minPrimaryTermToKeep)
                    .collect(Collectors.toSet());
                primaryTermsToDelete.forEach(term -> deletePrimaryTermAsync(term));
            }

            @Override
            public void onFailure(Exception e) {
                logger.error("Exception occurred while getting primary terms from remote store", e);
            }
        });
    }

    /**
     * Handles deletion of all translog files associated with a primary term.
     *
     * @param primaryTerm primary term.
     */
    private void deletePrimaryTermAsync(long primaryTerm) {
        transferService.deleteAsync(
            ThreadPool.Names.REMOTE_PURGE,
            remoteBaseTransferPath.add(String.valueOf(primaryTerm)),
            new ActionListener<>() {
                @Override
                public void onResponse(Void unused) {
                    logger.info("Deleted primary term {}", primaryTerm);
                }

                @Override
                public void onFailure(Exception e) {
                    logger.error(new ParameterizedMessage("Exception occurred while deleting primary term {}", primaryTerm), e);
                }
            }
        );
    }
}
