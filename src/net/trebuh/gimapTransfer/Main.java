package net.trebuh.gimapTransfer;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.mail.Address;
import javax.mail.Authenticator;
import javax.mail.FetchProfile;
import javax.mail.Flags.Flag;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.Message.RecipientType;
import javax.mail.MessagingException;
import javax.mail.NoSuchProviderException;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.search.AndTerm;
import javax.mail.search.ComparisonTerm;
import javax.mail.search.FromTerm;
import javax.mail.search.ReceivedDateTerm;
import javax.mail.search.RecipientTerm;
import javax.mail.search.SearchTerm;
import javax.mail.search.SentDateTerm;
import javax.mail.search.SubjectTerm;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sun.mail.gimap.GmailFolder;
import com.sun.mail.gimap.GmailMessage;
import com.sun.mail.gimap.GmailMsgIdTerm;

public class Main {
    /*
     * le transfert peut mettre plusieurs heures et être interrompu: il faut
     * prévoir une forme de retour (logging), et une forme de sauvegarde de
     * journalisation qui permette de reprendre une opération interrompu.
     * 
     * Il faut aussi prévoir que dossiers source et destination peuvent être
     * modifiés pendant le transfert, et que l'email source peut être supprimé
     * pendant le transfert.
     * 
     * Si ça s'exécute chez OVH, ça ira bien plus vite.
     * 
     * Il y a probablement déjà des doublons: un autre programme pourra servir à
     * rechercher et supprimer les doublons.
     * 
     * faire un programme qui copie tous les emails depuis gmail vers des
     * dossiers IMAP en conservant l’unicité des messages, quite à avoir un
     * hiérarchie de dossier « bizarre ». Par exemple, trier les labels par
     * ordre alphabétique, ignorer les labels automatiques s’ils ne sont pas
     * seuls (important, peu important, notifications, forums, promotions, etc.)
     * (ou les convertir en label IMAP) et s’il restent les labels, L1, L2, L3,
     * mettre ce message dans le dossier L1\L2\L3. Ça n’est pas parfait mais ça
     * permet de conserver l’unicité des messages et ne pas perdre
     * d’information. Je pourrais refaire un tri manuel par la suite.
     */
    /*
     * @formatter:off
     * Chaque e-mail Gmail possède un identifiant unique.
     * - avant chaque copie, regarder si l'identifiant est dans la base des e-mails déjà transférer
     * - si oui, ne pas le retransférer,
     * - sinon, s'il est dans la base des transfert en cours (base qui sera au plus un singleton si c'est
     * mono-threadé),
     *   - rechercher ce mail dans les dossiers destination et le supprimer des dossiers destinations
     *     avant de reprendre le transfert,
     * - sinon, le transférer.
     * 
     * Le transfert consiste en:
     * - ajouter l'identifiant au transfert en cours,
     * - copier l'email
     * - ajouter l'identifiant à la bases des e-mail déjà transférés
     * - supprimer l'identifiant des e-mail en cours de transfer
     * 
     * @formatter:on
     */
    /*
     * Les bases ("transféré" et "en cours") doivent toujours être synchrone
     * avec un fichier correspondant sur disque.
     * 
     * Au démarage, lister tous les e-mails distant (leurs identifiants) et
     * retirer les emails se trouvant dans la base des e-mail déjà transféré:
     * cela donne la base de "TO DO". Au passage, cela signifie que l'on
     * pourrait reprendre le transfert même après qu'il ai été terminé afin de
     * récupérer les nouveau emails. Ensuite, regarder si la base des transferts
     * en cours est vide, sinon, commencer par le transfert de ce message.
     * 
     * La phase de démarrage n'est pas vraiment parallèlisable, mais une fois la
     * liste de "TO DO" construite, on doit pouvoir parallèliser les transferts.
     * C'est d'autant plus intéressant que les écritures disque doivent être
     * synchrones et peuvent donc faire perdre du temps. Cela veut probablement
     * dire qu'il faut N+1 threads: N pour la copie, 1 pour l'interface (qui
     * toutes les 10 secondes affiche un message de status)
     */

    /**
     * Messages smaller than this size (in bytes) are considered small messages
     * and are fetched in batch.
     */
    private static final int SMALL_MESSAGE_SIZE = 10 * 1024;
    /**
     * Tries to download (fetch) small messages in batch of at least this size
     * (in bytes)
     */
    private static final int SMALL_MESSAGE_BATCH_SIZE = 10 * 1024 * 1024;
    /**
     * Refresh rate in milliseconds of status messages
     */
    private static final int REFRESH_RATE = 200;
    private static final String GIMAP = "gimap";
    private static final String GOOGLE_HOST = "imap.gmail.com";

    private static Logger log = Logger.getLogger("Main");

    public static void main(final String[] args) {

        ParsedArguments options = new ParsedArguments(args);

        log.setLevel(Level.ALL);
        try {
            final Session instance = getSession(options);
            log.info("Connecting to " + GOOGLE_HOST + "...");
            final Store gimapStore = instance.getStore(GIMAP);
            gimapStore.connect(GOOGLE_HOST, options.googleUser, null);
            log.info("Connected to " + gimapStore.getURLName());
            final Folder sourceRoot = gimapStore.getDefaultFolder();

            log.info("Connecting to " + options.targetHost + "...");
            final Store targetStore = instance.getStore(options.useSSL ? "imaps" : "imap");
            targetStore.connect(options.targetHost, options.targetPort, options.targetUser, null);
            log.info("Connected to " + gimapStore.getURLName());
            final Folder targetRoot = options.targetFolerName == null ? targetStore.getDefaultFolder()
                    : targetStore.getDefaultFolder().getFolder(options.targetFolerName);
            if (!targetRoot.exists())
                targetRoot.create(Folder.HOLDS_FOLDERS | Folder.HOLDS_MESSAGES);

            final FileBackedSet done = new FileBackedSet(new File("./done.txt"));
            log.info(String.format("%d messages have already been copied", done.size()));

            final FileBackedSet inProgress = new FileBackedSet(new File("./inProgress.txt"));
            if (inProgress.size() != 0)
                rollbackSession(sourceRoot, targetRoot, inProgress);

            final HashMap<Long, Folder> todo = getTodos(sourceRoot, done);

            Copier.of(todo, done, inProgress, targetRoot, SMALL_MESSAGE_SIZE, SMALL_MESSAGE_BATCH_SIZE, REFRESH_RATE,
                    Math.max(1, options.googlePoolSize), Math.max(1, options.targetPoolSize)).copyMessages();

        } catch (final NoSuchProviderException e) {
            e.printStackTrace();
            System.exit(1);
        } catch (final MessagingException e) {
            e.printStackTrace();
            System.exit(1);
        } catch (final IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Removes in <code>targetRoot</code> the messages referenced in
     * <code>inProgress</code> that are in <code>sourceRoot</code>.
     * 
     * @param sourceRoot
     *            the Google root folder that in which the source messages are
     *            stored (or in a sub-folder of this folder, recursively)
     * @param targetRoot
     *            the root folder in which the messages have (possibly) been
     *            copied (and which does not need to be a Google folder)
     * @param inProgress
     *            unique google identifiers of messages in sourceRoot that have
     *            only partially been copied to targetRoot
     * @throws MessagingException
     *             in case of error
     * @throws IOException
     *             if <code>inProgress</code> cannot be cleared
     */
    private static void rollbackSession(final Folder sourceRoot, final Folder targetRoot,
            final FileBackedSet inProgress) throws MessagingException, IOException {
        log.info("This program has been interrupted during a copy... retreiving the messages to remove");
        for (final long msgid : inProgress.getContents()) {
            log.info("Looking for google message with id " + msgid + "...");
            search(new GmailMsgIdTerm(msgid), sourceRoot, Folder.READ_ONLY, srcMsg -> {
                log.info("Found google message with id " + msgid
                        + ". Retreiving its properties and looking for a corresponding message in the target store...");
                final List<SearchTerm> searchTerms = Lists.newArrayList();
                final Date sentDate = srcMsg.getSentDate();
                if (sentDate != null)
                    searchTerms.add(new SentDateTerm(ComparisonTerm.EQ, sentDate));
                final Date receivedDate = srcMsg.getReceivedDate();
                if (receivedDate != null)
                    searchTerms.add(new ReceivedDateTerm(ComparisonTerm.EQ, receivedDate));
                if (searchTerms.isEmpty())
                    throw new IllegalStateException(
                            "No date in message... other fields are not sufficiently identifiying");
                final Address[] from = srcMsg.getFrom();
                if (from != null)
                    for (final Address address : from)
                        searchTerms.add(new FromTerm(address));
                final Address[] toRecipients = srcMsg.getRecipients(RecipientType.TO);
                if (toRecipients != null)
                    for (final Address address : toRecipients)
                        searchTerms.add(new RecipientTerm(RecipientType.TO, address));
                final Address[] ccRecipients = srcMsg.getRecipients(RecipientType.CC);
                if (ccRecipients != null)
                    for (final Address address : ccRecipients)
                        searchTerms.add(new RecipientTerm(RecipientType.CC, address));
                final String subject = srcMsg.getSubject();
                if (subject != null)
                    searchTerms.add(new SubjectTerm(subject));
                if (searchTerms.size() < 4)
                    throw new IllegalStateException("Not enough search terms found in a message");
                final AndTerm term = new AndTerm(searchTerms.toArray(new SearchTerm[searchTerms.size()]));
                /*
                 * This search is expensive and could be greatly improved
                 * because we could know in which folder to search...
                 */
                search(term, targetRoot, Folder.READ_WRITE, message -> {
                    log.info("Found a corresponding message on the server, deleting it.");
                    message.setFlag(Flag.DELETED, true);
                });
            });
        }
        inProgress.clear();
    }

    /**
     * @param options
     * @return a session with a console password authenticator
     */
    private static Session getSession(ParsedArguments options) {
        final Properties props = System.getProperties();
        props.setProperty("mail.imaps.appendbuffersize", Integer.toString(SMALL_MESSAGE_SIZE));
        if (options.useStartTLS)
            props.setProperty("mail.imap.starttls.required", "true");
        if (options.targetPoolSize > 0)
            props.setProperty("mail." + (options.useSSL ? "imaps" : "imap") + ".connectionpoolsize",
                    Integer.toString(options.targetPoolSize));
        if (options.googlePoolSize > 0)
            props.setProperty("mail.gimap.connectionpoolsize", Integer.toString(options.googlePoolSize));

        final Authenticator authenticator = new ConsolePasswordAuthenticator();
        return Session.getInstance(props, authenticator);
    }

    private static HashMap<Long, Folder> getTodos(final Folder sourceRoot, final FileBackedSet done) {
        log.info("Counting messages...");
        final int[] nbMessages = new int[] { 0 };
        final Thread t = new Thread(() -> countMessages(nbMessages, sourceRoot), "Thread-messageCounter");
        t.start();
        while (t.isAlive()) {
            try {
                t.join(REFRESH_RATE);
            } catch (final InterruptedException e) {
                t.interrupt();
                System.exit(1);
            }
            System.err.print(String.format("\rCounting messages... %d found", nbMessages[0]));
        }
        System.err.println();
        log.info(String.format("Found %d messages (including duplicates) on source server", nbMessages[0]));
        if (nbMessages[0] == 0)
            System.exit(0);

        log.info("Computing messages to copy...");
        final HashMap<Long, Folder> todo = Maps.newHashMapWithExpectedSize(nbMessages[0] - done.size());
        final Thread t2 = new Thread(() -> computeTODO(done, todo, sourceRoot, nbMessages), "Thread-computeTODO");
        t2.start();
        while (t2.isAlive()) {
            try {
                t2.join(REFRESH_RATE);
            } catch (final InterruptedException e) {
                t2.interrupt();
                System.exit(1);
            }
            if (nbMessages[0] - done.size() != 0)
                System.err.print(
                        String.format("\r...%5.1f%%", (todo.size() * 100f) / ((float) (nbMessages[0] - done.size()))));
            else {
                System.err.print("\r...100.0%");
            }
        }
        System.err.println(String.format("\r...100.0%% Found %d messages to copy", todo.size()));
        return todo;
    }

    /**
     * Recursively search in <code>root</code> folder for the messages that
     * matches <code>term</code> and apply <code>cons</code> on the found
     * messages.
     * 
     * @param term
     * @param root
     * @param mode
     *            {@link Folder#READ_ONLY} or {@link Folder#READ_WRITE}
     * @param cons
     * @throws MessagingException
     *             in case of error
     */
    private static void search(final SearchTerm term, final Folder root, final int mode,
            final MailConsumer<Message> cons) throws MessagingException {
        if ((root.getType() & Folder.HOLDS_MESSAGES) != 0) {
            if (!root.isOpen())
                root.open(mode);
            for (final Message msg : root.search(term))
                cons.apply(msg);
            root.close(mode == Folder.READ_WRITE);
        }
        if ((root.getType() & Folder.HOLDS_FOLDERS) != 0)
            for (final Folder folder : root.list())
                search(term, folder, mode, cons);
    }

    /**
     * @param done
     *            the set of messages that have already been copied
     * @param todo
     *            the set in which messages that should be copied will be put,
     *            indexed by their Google Unique Identifier
     * @param root
     *            the folder in which messages should be (recursively) looked
     *            for
     * @param nbMessages
     *            will be updated (decremented) if duplicates are found while
     *            looking for messages to be done
     */
    private static void computeTODO(final FileBackedSet done, final HashMap<Long, Folder> todo, final Folder root,
            final int[] nbMessages) {
        try {
            if ((root.getType() & Folder.HOLDS_MESSAGES) != 0) {
                if (!root.isOpen())
                    root.open(Folder.READ_ONLY);
                final Message[] messages = root.getMessages();
                final FetchProfile fp = new FetchProfile();
                fp.add(GmailFolder.FetchProfileItem.MSGID);
                root.fetch(messages, fp);
                for (final Message message : messages) {
                    final GmailMessage gMsg = (GmailMessage) message;
                    final Long msgId = gMsg.getMsgId();
                    if (todo.containsKey(msgId))
                        nbMessages[0]--;
                    else if (done.contains(msgId))
                        continue;
                    else
                        todo.put(msgId, root);
                }
                root.close(false);
            }
            if ((root.getType() & Folder.HOLDS_FOLDERS) != 0)
                for (final Folder folder : root.list())
                    computeTODO(done, todo, folder, nbMessages);
        } catch (final MessagingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Count recursively the messages in <code>root</code> folder, updating
     * <code>nbMessages</code> on the way.
     * 
     * @param nbMessages
     *            a reference (an array with exactly one element)
     * @param root
     * @throws IllegalStateException
     *             with a cause in case of an error
     */
    static void countMessages(final int[] nbMessages, final Folder root) {
        try {
            if ((root.getType() & Folder.HOLDS_MESSAGES) != 0) {
                int messageCount = root.getMessageCount();
                if (messageCount == -1) {
                    if (!root.isOpen())
                        root.open(Folder.READ_ONLY);
                    messageCount = root.getMessageCount();
                    root.close(false);
                }
                nbMessages[0] += messageCount;
            }
            if ((root.getType() & Folder.HOLDS_FOLDERS) != 0)
                for (final Folder folder : root.list())
                    countMessages(nbMessages, folder);
        } catch (final MessagingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Like {@link Function}, but the apply method can throw a
     * {@link MessagingException}.
     * 
     * @author Laurent Hubert-Vaillant
     *
     * @param <T>
     * @param <R>
     */
    interface MailFunction<T, R> {
        abstract R apply(T f) throws MessagingException;
    }

    /**
     * Like {@link Consumer}, but the apply method can throw a
     * {@link MessagingException}.
     * 
     * @author Laurent Hubert-Vaillant
     * @param <T>
     */
    interface MailConsumer<T> {
        abstract void apply(T f) throws MessagingException;
    }
}
