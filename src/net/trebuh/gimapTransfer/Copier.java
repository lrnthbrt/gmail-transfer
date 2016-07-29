package net.trebuh.gimapTransfer;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

import javax.mail.FetchProfile;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.StoreClosedException;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimaps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.sun.mail.gimap.GmailFolder;
import com.sun.mail.gimap.GmailMessage;
import com.sun.mail.imap.IMAPFolder;

public class Copier {
    private static final Logger log = Logger.getLogger(Copier.class.getName());
    private final FetchProfile prefetch;
    private final FetchProfile fetchAll;
    private final ImmutableSetMultimap<Folder, Long> todo;
    private final FileBackedSet done;
    private final FileBackedSet inProgress;
    private final Folder targetRoot;
    private final int smallMessageSize;
    private final long smallMessageBatchSize;
    private final int refreshRate;
    private final int googlePoolSize;
    private final int targetPoolSize;
    /**
     * a cache of target folders to avoid having to check if the folders exist
     * (and keeping a set of folder names that are known to exist is not enough
     * as it will be checked internally by the folder if it is a new handled)
     */
    private final ThreadLocal<Map<List<String>, Folder>> cache;

    private Copier(ImmutableSetMultimap<Folder, Long> todo, FileBackedSet done, FileBackedSet inProgress,
            final Folder targetRoot, int smallMessageSize, int smallMessageBatchSize, int refreshRate,
            int googlePoolSize, int targetPoolSize) {
        this.todo = todo;
        this.done = done;
        this.inProgress = inProgress;
        this.targetRoot = targetRoot;
        this.smallMessageSize = smallMessageSize;
        this.smallMessageBatchSize = smallMessageBatchSize;
        this.refreshRate = refreshRate;
        this.googlePoolSize = googlePoolSize;
        this.targetPoolSize = targetPoolSize;

        prefetch = new FetchProfile();
        prefetch.add(GmailFolder.FetchProfileItem.MSGID);
        prefetch.add(GmailFolder.FetchProfileItem.LABELS);
        prefetch.add(FetchProfile.Item.SIZE);

        fetchAll = new FetchProfile();
        fetchAll.add(IMAPFolder.FetchProfileItem.MESSAGE);
        this.cache = new ThreadLocal<Map<List<String>, Folder>>() {
            @Override
            protected Map<List<String>, Folder> initialValue() {
                return Maps.newHashMap();
            }
        };

    }

    /**
     * Performs the actual copy on the server
     * 
     * @param todo
     *            the message to copy. This set is not modified.
     * @param done
     *            the message that have been copied. This set may not be empty
     *            at beginning but it will not be read. This set will contain
     *            all message identifiers referenced in <code>todo</code> upon
     *            normal completion of the method
     * @param inProgress
     *            if this method does not terminate normally, it will contain
     *            the message (or messages) that may not have been fully copied.
     * @param targetRoot
     *            the root folder into which messages should be copied (which
     *            should be able to contain other folders and messages)
     * @param smallMessageSize
     *            Messages smaller than this size (in bytes) are considered
     *            small messages and are fetched in batch
     * @param smallMessageBatchSize
     *            tries to download (fetch) small messages in batch of at least
     *            this size (in bytes)
     * @param refreshRate
     *            Refresh rate in milliseconds of status messages
     * @param googlePoolSize
     *            number of simultaneous connections
     * @param targetPoolSize
     *            number of simultaneous connections
     * @throws MessagingException
     * @throws IOException
     */
    public static Copier of(final HashMap<Long, Folder> todo, final FileBackedSet done, final FileBackedSet inProgress,
            final Folder targetRoot, int smallMessageSize, int smallMessageBatchSize, int refreshRate,
            int googlePoolSize, int targetPoolSize) throws MessagingException {
        Preconditions.checkArgument(inProgress.size() == 0);
        Preconditions.checkArgument((targetRoot.getType() & Folder.HOLDS_FOLDERS) != 0);
        Preconditions.checkArgument((targetRoot.getType() & Folder.HOLDS_MESSAGES) != 0);
        Preconditions.checkArgument(googlePoolSize > 0);
        Preconditions.checkArgument(targetPoolSize > 0);
        final SetMultimap<Folder, Long> revTodo = Multimaps.invertFrom(Multimaps.forMap(todo), HashMultimap.create());
        return new Copier(ImmutableSetMultimap.copyOf(revTodo), done, inProgress, targetRoot, smallMessageSize,
                smallMessageBatchSize, refreshRate, googlePoolSize, targetPoolSize);
    }

    public void copyMessages() throws MessagingException, IOException {

        if (todo.isEmpty())
            return;

        final LongAdder copiedSize = new LongAdder();

        final LoadingCache<List<String>, ExecutorService> consumers = CacheBuilder.newBuilder()
                .build(new CacheLoader<List<String>, ExecutorService>() {
                    @Override
                    public ExecutorService load(List<String> key) throws Exception {
                        return Executors.newSingleThreadExecutor();
                    }
                });

        final ExecutorService producers = Executors.newFixedThreadPool(googlePoolSize);
        for (final Entry<Folder, Collection<Long>> entry : todo.asMap().entrySet()) {
            producers.submit(() -> {
                try {
                    final Folder folder = entry.getKey();
                    final Set<Long> msgids = (Set<Long>) entry.getValue();
                    log.info(String.format("Starting folder %s (%s messages to do)", folder.getFullName(),
                            msgids.size()));

                    if (!folder.isOpen())
                        folder.open(Folder.READ_ONLY);
                    ArrayList<Future<?>> futures = Lists.newArrayList();
                    final List<String> folderNameBasedLabels = Splitter.on(folder.getSeparator())
                            .splitToList(folder.getFullName());
                    final Message[] allFolderMessages = folder.getMessages();
                    folder.fetch(allFolderMessages, prefetch);
                    ArrayList<GmailMessage> smallMessages = Lists.newArrayListWithCapacity(allFolderMessages.length);
                    long smallMessagesSize = 0;
                    for (final Message msg : allFolderMessages) {
                        final GmailMessage gmsg = (GmailMessage) msg;
                        final Long msgId = gmsg.getMsgId();
                        if (!msgids.contains(msgId))
                            continue;
                        if (gmsg.getSize() < smallMessageSize) {
                            smallMessages.add(gmsg);
                            smallMessagesSize += gmsg.getSize();
                            if (smallMessagesSize > smallMessageBatchSize) {
                                folder.fetch((Message[]) smallMessages.toArray(new Message[smallMessages.size()]),
                                        fetchAll);
                                if (!targetRoot.getStore().isConnected())
                                    targetRoot.getStore().connect();
                                for (GmailMessage message : smallMessages) {
                                    final List<String> targetPath = getFolderName(folderNameBasedLabels, message);
                                    futures.add(consumers.getUnchecked(targetPath)
                                            .submit(new Consumer(this, targetPath, copiedSize, message)));
                                }
                            }
                            smallMessages.clear();
                            smallMessagesSize = 0;
                        } else {
                            folder.fetch(new Message[] { gmsg }, fetchAll);
                            final List<String> targetPath = getFolderName(folderNameBasedLabels, gmsg);
                            futures.add(consumers.getUnchecked(targetPath)
                                    .submit(new Consumer(this, targetPath, copiedSize, gmsg)));
                        }
                    }
                    for (Future<?> future : futures)
                        future.get();
                    folder.close(false);
                    log.info("Finished folder " + folder.getFullName());
                } catch (Exception e) {
                    e.printStackTrace(System.err);
                    throw new RuntimeException(e);
                }
            });
        }
        producers.shutdown();

        final float wereTodo = todo.size();
        final int initallyDone = done.size();
        System.err.println();
        final long startTime = System.nanoTime();
        long prevTime = startTime;
        long lastCopied = 0;
        boolean finished = false;
        while (!finished) {
            try {
                finished = producers.awaitTermination(refreshRate, TimeUnit.MILLISECONDS);
                if (finished) {
                    for (ExecutorService consumer : consumers.asMap().values())
                        if (!(finished = consumer.awaitTermination(refreshRate, TimeUnit.MILLISECONDS)))
                            break;
                }
            } catch (InterruptedException e) {
                e.printStackTrace(System.err);
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            {
                /* displaying progress */
                final long copied = copiedSize.longValue();
                final String copiedString = humanReadableSize(copied);

                final long copiedSinceLast = copied - lastCopied;
                lastCopied = copied;
                final long currTime = System.nanoTime();
                final long elapseTimeSinceLast = currTime - prevTime;
                final long elapseTime = currTime - startTime;
                prevTime = currTime;
                final String rateSinceLast = humanReadableSize(copiedSinceLast * 1000000000L / elapseTimeSinceLast);
                final String rate = humanReadableSize(copied * 1000000000L / elapseTime);

                final float perCentDone = (float) ((done.size() - initallyDone) * 100) / wereTodo;
                System.err.print(String.format(Locale.US,
                        "\r%5.1f%% done (%s copied, current rate: %s/s, average rate: %s/s)          ", perCentDone,
                        copiedString, rateSinceLast, rate));
            }
        }

        System.out.println("finished !!!");
    }

    /**
     * 
     * @param bytes
     * @return a string representation of the given bytes, choosing the unit to
     *         either display the value in B (bytes), KiB (kibibytes,), or MiB
     *         (mebibytes)
     */
    private static String humanReadableSize(Long bytes) {
        if (bytes < 10000)
            return String.format("%,5d B", bytes);
        else if (bytes / 1024 < 10000)
            return String.format("%,5d KiB", bytes / 1024);
        else
            return String.format("%,5d MiB", bytes / (1024 * 1024));
    }

    private static final class Consumer implements Runnable {
        static final Object notifier = new Object();
        static final AtomicInteger activeConsumers = new AtomicInteger(0);
        private final List<String> targetPath;
        private final LongAdder copiedSize;
        private final GmailMessage gmsg;
        private final Copier copier;

        private Consumer(Copier copier, List<String> targetPath, LongAdder copiedSize, GmailMessage gmsg) {
            this.targetPath = targetPath;
            this.copiedSize = copiedSize;
            this.gmsg = gmsg;
            this.copier = copier;
        }

        @Override
        public void run() {
            try {
                synchronized (notifier) {
                    while (activeConsumers.get() >= copier.targetPoolSize)
                        notifier.wait();
                    activeConsumers.incrementAndGet();
                }
                copier.copyMessage(gmsg, targetPath);
                copier.done.add(gmsg.getMsgId());
                copiedSize.add(gmsg.getSize());
                synchronized (notifier) {
                    activeConsumers.decrementAndGet();
                    notifier.notify();
                }
            } catch (Exception e) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw, true);
                e.printStackTrace(pw);
                log.severe(sw.toString());
                throw new RuntimeException(e);
            }
        }
    }

    private Folder getFolder(List<String> targetPath) {
        if (targetPath.isEmpty())
            return targetRoot;
        List<String> parentPath = targetPath.subList(0, targetPath.size() - 1);
        Folder parent = cache.get().computeIfAbsent(parentPath, p -> getFolder(p));
        try {
            Folder folder = parent.getFolder(targetPath.get(targetPath.size() - 1));
            if (!folder.exists())
                folder.create(Folder.HOLDS_FOLDERS | Folder.HOLDS_MESSAGES);
            return folder;
        } catch (MessagingException e) {
            throw new RuntimeException(e);
        }
    }

    protected void copyMessage(final GmailMessage gmsg, final List<String> targetPath) throws MessagingException {
        boolean hasFailed = false;
        while (true)
            try {
                Folder targetFolder = cache.get().computeIfAbsent(targetPath, p -> getFolder(p));
                targetFolder.appendMessages(new Message[] { gmsg });
                return;
            } catch (StoreClosedException e) {
                if (!hasFailed)
                    // try again once
                    if (!targetRoot.getStore().isConnected())
                    targetRoot.getStore().connect();
                    else
                    throw e;
                else
                    throw e;
            }
    }

    /**
     * 
     * @param folderNameBasedLabels
     *            the labels obtained from the folder name (excluding [Gmail] if
     *            any)
     * @param gmsg
     *            the messages to copy
     * @return the full name of the folder relative to the root target directory
     *         (which does not need to be the root of the store: all copied
     *         messages may be stored in a hierarchy rooted in a sub-folder)
     * @throws MessagingException
     */
    private static List<String> getFolderName(final List<String> folderNameBasedLabels, final GmailMessage gmsg)
            throws MessagingException {
        final TreeSet<String> labels = Sets.newTreeSet(String::compareToIgnoreCase);
        Iterables.addAll(labels, Iterables.transform(Arrays.asList(gmsg.getLabels()), (final String s) -> {
            if (!s.isEmpty() && s.charAt(0) == '\\')
                return s.substring(1);
            else
                return s;
        }));
        labels.addAll(folderNameBasedLabels);
        labels.remove("INBOX"); // This is a reserved name: we will put
                                // messages at the root
        labels.remove("[Gmail]"); // not meaningful
        for (final String label : Lists.newArrayList(labels)) {
            if (label.contains("/")) {
                labels.remove(label);
                Iterables.addAll(labels, Splitter.on('/').split(label));
            }
        }
        if (labels.size() != 1 && labels.contains("Important"))
            labels.remove("Important");
        if (labels.size() != 1 && labels.contains("Sent"))
            labels.remove("Sent");
        if (labels.size() != 1 && labels.contains("Messages envoyés"))
            labels.remove("Messages envoyés");
        return Lists.newArrayList(labels);
    }
}