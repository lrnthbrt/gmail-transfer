/**
 * 
 */
package net.trebuh.gimapTransfer;

import static com.google.common.base.Preconditions.checkNotNull;

import java.text.ParseException;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.mail.FetchProfile;
import javax.mail.Flags.Flag;
import javax.mail.Folder;
import javax.mail.Header;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.internet.MailDateFormat;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.sun.mail.imap.IMAPFolder;

/**
 * @author laurent
 *
 */
public class DuplicatesFinder {

    public static class MessageSummary {
        private static class HashHeader {
            private Header header;

            public HashHeader(Header header) {
                this.header = checkNotNull(header);
            }

            @Override
            public int hashCode() {
                final int prime = 31;
                int result = 1;
                String name = header.getName();
                String value = header.getValue();
                result = prime * result + ((name == null) ? 0 : name.hashCode());
                result = prime * result + ((value == null) ? 0 : value.hashCode());
                return result;
            }

            @Override
            public boolean equals(Object obj) {
                if (this == obj)
                    return true;
                if (obj == null)
                    return false;
                if (getClass() != obj.getClass())
                    return false;
                HashHeader other = (HashHeader) obj;
                String name = header.getName();
                String value = header.getValue();
                String otherName = other.header.getName();
                String otherValue = other.header.getValue();
                if (name == null) {
                    if (otherName != null)
                        return false;
                } else if (!name.equals(otherName))
                    return false;
                if (value == null) {
                    if (otherValue != null)
                        return false;
                } else if (!value.equals(otherValue))
                    return false;
                return true;
            }

            @Override
            public String toString() {
                return header.getName() + ": " + header.getValue();
            }
        }

        private static final FetchProfile FP;
        static {
            FetchProfile fp = new FetchProfile();
            fp.add(IMAPFolder.FetchProfileItem.HEADERS);
            FP = fp;
        }

        private final ImmutableSetMultimap<String, String> headers;

        private MessageSummary(ImmutableSetMultimap<String, String> headers) {
            this.headers = headers;
        }

        public static MessageSummary of(Message msg) throws MessagingException {
            @SuppressWarnings("unchecked")
            Enumeration<Header> allHeadersEnum = msg.getAllHeaders();
            ImmutableSetMultimap.Builder<String, String> builder = ImmutableSetMultimap.builder();
            while (allHeadersEnum.hasMoreElements()) {
                Header next = allHeadersEnum.nextElement();
                builder.put(next.getName(), next.getValue());
            }
            return new MessageSummary(builder.build());
        }

        public static FetchProfile getProfile() {
            return FP;
        }

        @Override
        public int hashCode() {
            return headers.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            MessageSummary other = (MessageSummary) obj;
            return headers.equals(other.headers);
        }

        @Override
        public String toString() {
            return String.format("Message=[Date:%s\n\tFrom: %s,\n\tTo: %s,\n\tSubject: %s]", headers.get("Date"),
                    headers.get("From"), headers.get("To"), headers.get("Subject"));
        }

        public static final Comparator<MessageSummary> dateComparator = new Comparator<MessageSummary>() {
            MailDateFormat mailDateFormat = new MailDateFormat();

            @Override
            public int compare(MessageSummary o1, MessageSummary o2) {
                String sdate1 = Iterables.getFirst(o1.headers.get("Date"), null);
                String sdate2 = Iterables.getFirst(o2.headers.get("Date"), null);
                Date date1;
                try {
                    date1 = sdate1 == null ? null : mailDateFormat.parse(sdate1);
                } catch (ParseException e1) {
                    date1 = null;
                }
                Date date2;
                try {
                    date2 = sdate2 == null ? null : mailDateFormat.parse(sdate2);
                } catch (ParseException e1) {
                    date2 = null;
                }

                if (date1 == null) {
                    if (date2 == null)
                        return 0;
                    return -1;
                } else if (date2 == null)
                    return 1;
                return date1.compareTo(date2);
            }

        };
    }

    private static final Logger log = Logger.getLogger(DuplicatesFinder.class.getName());
    private final Folder rootFolder;
    private final Set<String> ignoredFolders;

    /**
     * 
     */
    public DuplicatesFinder(Folder rootFolder, Set<String> ignoredFolders) {
        this.rootFolder = rootFolder;
        this.ignoredFolders = ImmutableSet.copyOf(ignoredFolders);
    }

    /**
     * @param args
     * @throws MessagingException
     */
    public static void main(String[] args) throws MessagingException {
        log.setLevel(Level.ALL);
        final Properties props = System.getProperties();
        final Session instance = Session.getInstance(props, new ConsolePasswordAuthenticator());
        Store store = instance.getStore("imaps");
        store.connect("ex2.mail.ovh.net", 993, "laurent@trebuh.net", null);
        // Store store = instance.getStore("gimap");
        // store.connect("imap.gmail.com", "lrnthbrt@gmail.com", null);
        Folder rootFolder = store.getDefaultFolder();
        log.info("Looking for duplicates...");
        ImmutableSet<String> ignoredFolders = ImmutableSet.of("Contacts", "EmailedContacts", "Calendrier");
        // new DuplicatesFinder(rootFolder, ignoredFolders).deleteDuplicates();
        Map<MessageSummary, List<Folder>> duplicates = new DuplicatesFinder(rootFolder, ignoredFolders)
                .findDuplicates();
        System.out.println(String.format("Found %d duplicates:", duplicates.keySet().size()));
        Ordering<Entry<MessageSummary, List<Folder>>> duplicatesOrd = Ordering.from(String::compareTo)
                .onResultOf((Entry<?, List<Folder>> e) -> Iterables.get(e.getValue(), 0).getFullName())
                .compound(Ordering.from(MessageSummary.dateComparator).onResultOf(Entry::getKey));
        for (Entry<MessageSummary, List<Folder>> duplicate : duplicatesOrd.sortedCopy(duplicates.entrySet())) {
            System.out.println(duplicate.getKey());
            for (Folder folder : duplicate.getValue()) {
                System.out.print("  in ");
                System.out.println(folder.getFullName());
            }
        }
        System.err.flush();
        System.out.println("Finished!");
    }

    /**
     * 
     * @return a map of message summaries that are duplicated: the messages are
     *         very very likely to be duplicates
     * @throws MessagingException
     */
    public Map<MessageSummary, List<Folder>> findDuplicates() throws MessagingException {
        ListMultimap<MessageSummary, Folder> summaries = ArrayListMultimap.create();
        log.info("Indexing messages...");
        find(rootFolder, summaries);
        log.info(String.format("Found %d different message summaries (and %d messages)", summaries.keySet().size(),
                summaries.size()));
        /* removing non-duplicates */

        Iterator<Entry<MessageSummary, List<Folder>>> iterator = Multimaps.asMap(summaries).entrySet().iterator();
        while (iterator.hasNext())
            if (iterator.next().getValue().size() < 2)
                iterator.remove();
        return Multimaps.asMap(summaries);
    }

    /**
     * Register all messages in folder and its sub-folders in accumulator,
     * mapping for each message summary the folder(s) in which it has been seen.
     * If a same message summary is seen multiple times in a folder, it will be
     * reported multiple times.
     * 
     * @param folder
     * @param accumulator
     * @throws MessagingException
     */
    private void find(Folder folder, ListMultimap<MessageSummary, Folder> accumulator) throws MessagingException {
        if (ignoredFolders.contains(folder.getFullName()))
            return;
        if ((folder.getType() & Folder.HOLDS_MESSAGES) != 0) {
            log.info("Loading messages of folder " + folder.getFullName() + "...");
            if (!folder.isOpen())
                folder.open(Folder.READ_ONLY);
            Message[] messages = folder.getMessages();
            folder.fetch(messages, MessageSummary.getProfile());
            log.info("Indexing messages of folder " + folder.getFullName() + "...");
            try {
                for (Message message : messages)
                    accumulator.put(MessageSummary.of(message), folder);
                folder.close(false);
            } catch (MessagingException e) {
                System.err.println("Issue found while loading messages of folder " + folder.getFullName() + ": ");
                e.printStackTrace(System.err);
            }
        }
        if ((folder.getType() & Folder.HOLDS_FOLDERS) != 0)
            for (Folder subFolder : folder.list())
                find(subFolder, accumulator);
    }

    private void deleteDuplicates() throws MessagingException {
        this.deleteDuplicates(rootFolder, Sets.newHashSet());
    }

    private void deleteDuplicates(Folder folder, Set<MessageSummary> seen) throws MessagingException {
        if (ignoredFolders.contains(folder.getFullName()))
            return;
        if ((folder.getType() & Folder.HOLDS_MESSAGES) != 0) {
            log.info("Loading messages of folder " + folder.getFullName() + "...");
            if (!folder.isOpen())
                folder.open(Folder.READ_WRITE);
            Message[] messages = folder.getMessages();
            folder.fetch(messages, MessageSummary.getProfile());
            log.info("Indexing messages of folder " + folder.getFullName() + "...");
            try {
                for (Message message : messages) {
                    if (!seen.add(MessageSummary.of(message)))
                        message.setFlag(Flag.DELETED, true);
                }
                folder.close(true);
            } catch (MessagingException e) {
                System.err.println("Issue found while loading messages of folder " + folder.getFullName() + ": ");
                e.printStackTrace(System.err);
            }
        }
        if ((folder.getType() & Folder.HOLDS_FOLDERS) != 0)
            for (Folder subFolder : folder.list())
                deleteDuplicates(subFolder, seen);
    }

}
