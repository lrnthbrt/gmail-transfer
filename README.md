# gmail-transfer
A tool to transfer e-mails from Google's GMail to another IMAP account without duplicating e-mails.

In GMail, an e-mail with several labels will appear in several folders, for example "Important", "Personal" and "Project Foo". This tool copies all your e-mails on your gmail account into another IMAP server while keeping unicity of messages. To achieve this goal, a hiearchy of folders on the target server is created to keep the whole information, at the cost of some sub-folder being "duplicated". For example, let's say you have 3 e-mails, one tagged with "Personal" and "Project Foo",  another only with "Personal" and the last one with "Project Foo" only. You should get the first e-mail in "Personal/Project Foo", the second one in "Personal" and the last one in "Project Foo"... Notice that you now have two "Project Foo" folders, one at the root and another one in "Personal". However, from my experience, manualy sorting these folder is then straightforward.

Note that the current code:
- removes the label "Important" if it is not the only label, then
- removes the label "Sent" if it is not the only label, and then
- removes the label "Message envoyés" (because GMail folder names are localized and I am in France) if it is not the only label.

This is because I mostly do not use the automatic "Important" label set by Google on my messages, and because I prefer to have my sent messages with the rest of the conversation and not in a dedicated folder. However, this is likely to be a personal taste and you may change this... by editing the source code (the method `net.trebuh.gimapTransfer.Copier#getFolderName`). Sorry, this is not yet configurable. Tell me if you need this feature, and I will see if I can do something.

# Install
The recommanded usage is curently using Eclipse: open the project, then export the project as a runnable jar.
I have exported a Ant build script (antBuild.xml), but I have not tested it.

# Usage
See `java -jar gmail-transfer.jar --help`

# Contributions
Contributions are welcome, in the form of pull requests or bug reports.
