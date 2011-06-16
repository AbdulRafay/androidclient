package org.nuntius.ui;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Random;

import org.nuntius.R;
import org.nuntius.client.AbstractMessage;
import org.nuntius.client.ImageMessage;
import org.nuntius.client.MessageSender;
import org.nuntius.client.PlainTextMessage;
import org.nuntius.client.StatusResponse;
import org.nuntius.data.Contact;
import org.nuntius.data.Conversation;
import org.nuntius.data.MediaStorage;
import org.nuntius.provider.MessagesProvider;
import org.nuntius.provider.MyMessages.Messages;
import org.nuntius.service.MessageCenterService;
import org.nuntius.service.MessageResponseListener;
import org.nuntius.service.RequestJob;
import org.nuntius.service.MessageCenterService.MessageCenterInterface;

import android.app.AlertDialog;
import android.app.ListActivity;
import android.content.AsyncQueryHandler;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Contacts;
import android.text.ClipboardManager;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import android.widget.AdapterView.AdapterContextMenuInfo;


/**
 * Conversation writing activity.
 * @author Daniele Ricci
 * @version 1.0
 */
public class ComposeMessage extends ListActivity {
    private static final String TAG = ComposeMessage.class.getSimpleName();

    private static final int MESSAGE_LIST_QUERY_TOKEN = 8720;
    private static final int CONVERSATION_QUERY_TOKEN = 8721;

    private static final int REQUEST_CONTACT_PICKER = 9721;

    /** Used on the launch intent to pass the thread ID. */
    public static final String MESSAGE_THREAD_ID = "org.nuntius.message.threadId";
    /** Used on the launch intent to pass the peer of the thread. */
    public static final String MESSAGE_THREAD_PEER = "org.nuntius.message.peer";
    /** Used on the launch intent to pass the name of the peer. */
    public static final String MESSAGE_THREAD_USERNAME = "org.nuntius.message.user.name";
    /** Used on the launch intent to pass the phone of the peer. */
    public static final String MESSAGE_THREAD_USERPHONE = "org.nuntius.message.user.phone";

    /** View conversation intent action. Just provide the threadId with this. */
    public static final String ACTION_VIEW_CONVERSATION = "org.nuntius.thread.VIEW";

    private MessageListQueryHandler mQueryHandler;
    private MessageListAdapter mListAdapter;
    private EditText mTextEntry;

    /** The thread id. */
    private long threadId = -1;
    private Conversation mConversation;

    /** The SEND intent. */
    private Intent sendIntent;

    /** The user we are talking to. */
    private String userId;
    private String userName;
    private String userPhone;

    private final MessageListAdapter.OnContentChangedListener mContentChangedListener =
        new MessageListAdapter.OnContentChangedListener() {
        public void onContentChanged(MessageListAdapter adapter) {
            startQuery(true);
        }
    };

    /** Used by the service binder to receive responses from the request worker. */
    private MessageResponseListener mMessageSenderListener;

    /** Used for binding to the message center to send messages. */
    private class ComposerServiceConnection implements ServiceConnection {
        public final MessageSender job;
        private MessageCenterService service;

        public ComposerServiceConnection(String userId, String text, String mime, Uri uri) {
            job = new MessageSender(userId, text, mime, uri);
            job.setListener(mMessageSenderListener);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder ibinder) {
            MessageCenterInterface binder = (MessageCenterInterface) ibinder;
            service = binder.getService();
            service.sendMessage(job);
            unbindService(this);
        }
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.compose_message);

        mQueryHandler = new MessageListQueryHandler(getContentResolver());
        mListAdapter = new MessageListAdapter(this, null);
        mListAdapter.setOnContentChangedListener(mContentChangedListener);
        setListAdapter(mListAdapter);

        registerForContextMenu(getListView());

        mMessageSenderListener = new MessageResponseListener(this) {

            @Override
            public void response(RequestJob job, List<StatusResponse> res) {
                super.response(job, res);
            }

            @Override
            public boolean error(RequestJob job, Throwable e) {
                return super.error(job, e);
            }
        };

        mTextEntry = (EditText) findViewById(R.id.text_editor);
        Button sendButton = (Button) findViewById(R.id.send_button);
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendTextMessage();
            }
        });

        processIntent(savedInstanceState);
    }

    /** Sends out an image message. */
    private void sendImageMessage(Uri uri, String mime) {
        Log.i(TAG, "sending image: " + uri);
        Uri newMsg = null;
        String contents = null;

        try {
            // get image data
            InputStream in = getContentResolver().openInputStream(uri);
            byte[] buf = new byte[2048];
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (in.read(buf) >= 0)
                out.write(buf);
            in.close();

            byte[] fileData = out.toByteArray();
            out.close();

            // encode to base64 for sending
            contents = Base64.encodeToString(fileData, Base64.NO_WRAP);

            // store the file
            String msgId = "draft" + (new Random().nextInt());
            String filename = ImageMessage.buildMediaFilename(msgId, mime);
            MediaStorage.writeMedia(filename, fileData);

            // save to local storage
            ContentValues values = new ContentValues();
            // must supply a message ID...
            values.put(Messages.MESSAGE_ID, msgId);
            values.put(Messages.PEER, userId);
            values.put(Messages.MIME, mime);
            values.put(Messages.CONTENT, MediaStorage.URI_SCHEME + filename);
            values.put(Messages.UNREAD, false);
            values.put(Messages.DIRECTION, Messages.DIRECTION_OUT);
            values.put(Messages.TIMESTAMP, System.currentTimeMillis());
            values.put(Messages.STATUS, Messages.STATUS_SENDING);
            newMsg = getContentResolver().insert(Messages.CONTENT_URI, values);
        }
        catch (Exception e) {
            Log.e(TAG, "unable to store media", e);
        }

        if (newMsg != null) {

            // update thread id from the inserted message
            if (threadId <= 0) {
                Cursor c = getContentResolver().query(newMsg, new String[] { Messages.THREAD_ID }, null, null, null);
                if (c.moveToFirst()) {
                    threadId = c.getLong(0);
                    Log.i(TAG, "starting query with threadId " + threadId);
                    mConversation = null;
                    startQuery(true);
                }
                else
                    Log.i(TAG, "no data - cannot start query for this composer");
                c.close();
            }

            // send the message!
            ComposerServiceConnection conn = new ComposerServiceConnection(userId, contents, mime, newMsg);
            if (!bindService(
                    new Intent(getApplicationContext(), MessageCenterService.class),
                    conn, Context.BIND_AUTO_CREATE)) {
                // cannot bind :(
                mMessageSenderListener.error(conn.job, new IllegalArgumentException("unable to bind to service"));
            }
        }
        else {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(ComposeMessage.this,
                            "Unable to store message to outbox.",
                            Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    /** Sends out the text message in the composing entry. */
    private void sendTextMessage() {
        String text = mTextEntry.getText().toString();
        if (!TextUtils.isEmpty(text)) {
            Log.w(TAG, "sending message...");
            // save to local storage
            ContentValues values = new ContentValues();
            // must supply a message ID...
            values.put(Messages.MESSAGE_ID, "draft" + (new Random().nextInt()));
            values.put(Messages.PEER, userId);
            values.put(Messages.MIME, PlainTextMessage.MIME_TYPE);
            values.put(Messages.CONTENT, text);
            values.put(Messages.UNREAD, false);
            values.put(Messages.DIRECTION, Messages.DIRECTION_OUT);
            values.put(Messages.TIMESTAMP, System.currentTimeMillis());
            values.put(Messages.STATUS, Messages.STATUS_SENDING);
            Uri newMsg = getContentResolver().insert(Messages.CONTENT_URI, values);
            if (newMsg != null) {
                // empty text
                mTextEntry.setText("");

                // update thread id from the inserted message
                if (threadId <= 0) {
                    Cursor c = getContentResolver().query(newMsg, new String[] { Messages.THREAD_ID }, null, null, null);
                    if (c.moveToFirst()) {
                        threadId = c.getLong(0);
                        Log.i(TAG, "starting query with threadId " + threadId);
                        mConversation = null;
                        startQuery(true);
                    }
                    else
                        Log.i(TAG, "no data - cannot start query for this composer");
                    c.close();
                }

                // hide softkeyboard
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(mTextEntry.getWindowToken(), InputMethodManager.HIDE_IMPLICIT_ONLY);

                // send the message!
                ComposerServiceConnection conn = new ComposerServiceConnection(userId, text, PlainTextMessage.MIME_TYPE, newMsg);
                if (!bindService(
                        new Intent(getApplicationContext(), MessageCenterService.class),
                        conn, Context.BIND_AUTO_CREATE)) {
                    // cannot bind :(
                    mMessageSenderListener.error(conn.job, new IllegalArgumentException("unable to bind to service"));
                }
            }
            else {
                Toast.makeText(ComposeMessage.this,
                        "Unable to store message to outbox.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.compose_message_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean contactEnabled = ((mConversation != null) ? mConversation.getContact() != null : null);
        boolean threadEnabled = (threadId > 0);
        MenuItem i;

        i = menu.findItem(R.id.call_contact);
        i.setEnabled(contactEnabled);
        i = menu.findItem(R.id.view_contact);
        i.setEnabled(contactEnabled);
        i = menu.findItem(R.id.delete_thread);
        i.setEnabled(threadEnabled);

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.call_contact:
                startActivity(new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + userPhone)));
                return true;

            case R.id.view_contact:
                Contact contact = null;
                if (mConversation != null) {
                    contact = mConversation.getContact();
                    if (contact != null)
                        startActivity(new Intent(Intent.ACTION_VIEW, contact.getUri()));
                }
                return true;

            case R.id.delete_thread:
                if (threadId > 0)
                    deleteThread();

                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void deleteThread() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.confirm_delete_thread);
        builder.setIcon(android.R.drawable.ic_dialog_alert);
        builder.setMessage(R.string.confirm_will_delete_thread);
        builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                MessagesProvider.deleteThread(ComposeMessage.this, threadId);
            }
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.create().show();
    }

    private static final int MENU_FORWARD = 1;
    private static final int MENU_COPY_TEXT = 2;
    private static final int MENU_VIEW_IMAGE = 3;
    private static final int MENU_DELETE = 4;

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        MessageListItem vitem = (MessageListItem) info.targetView;
        AbstractMessage<?> msg = vitem.getMessage();

        menu.setHeaderTitle("Message options");
        menu.add(Menu.NONE, MENU_FORWARD, MENU_FORWARD, R.string.forward);

        if (msg instanceof ImageMessage) {
            if (((ImageMessage)msg).getMediaFilename() != null) {
            menu.add(Menu.NONE, MENU_VIEW_IMAGE, MENU_VIEW_IMAGE, "View image");
            }
        }
        else {
            menu.add(Menu.NONE, MENU_COPY_TEXT, MENU_COPY_TEXT, R.string.copy_message_text);
        }

        menu.add(Menu.NONE, MENU_DELETE, MENU_DELETE, "Delete message");
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        MessageListItem v = (MessageListItem) info.targetView;
        AbstractMessage<?> msg = v.getMessage();

        switch (item.getItemId()) {
            case MENU_FORWARD:
                // TODO message forwarding
                return true;

            case MENU_COPY_TEXT:
                Log.i(TAG, "copying message text: " + msg.getId());
                ClipboardManager cpm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cpm.setText(msg.getTextContent());

                Toast.makeText(this, R.string.message_text_copied, Toast.LENGTH_SHORT)
                    .show();
                return true;

            case MENU_VIEW_IMAGE:
                Log.i(TAG, "opening image");
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(MediaStorage.getMediaUri
                        (((ImageMessage)msg).getMediaFilename()), msg.getMime());
                startActivity(i);
                return true;

            case MENU_DELETE:
                Log.i(TAG, "deleting message: " + msg.getId());

                getContentResolver()
                    .delete(Messages.getUri(msg.getId()), null, null);
                return true;
        }

        return super.onContextItemSelected(item);
    }

    private void startQuery(boolean reloadConversation) {
        try {
            setProgressBarIndeterminateVisibility(true);

            Log.i(TAG, "starting query for thread " + threadId);
            AbstractMessage.startQuery(mQueryHandler, MESSAGE_LIST_QUERY_TOKEN, threadId);

            if (reloadConversation)
                Conversation.startQuery(mQueryHandler, CONVERSATION_QUERY_TOKEN, threadId);
        } catch (SQLiteException e) {
            Log.e(TAG, "query error", e);
        }
    }

    private void chooseContact() {
        Intent i = new Intent(Intent.ACTION_PICK, Contacts.CONTENT_URI);
        startActivityForResult(i, REQUEST_CONTACT_PICKER);
    }

    private void processSendIntent() {
        if (sendIntent != null) {
            final String mime = sendIntent.getType();
            // send text message - just fill the text entry
            if (PlainTextMessage.supportsMimeType(mime)) {
                mTextEntry.setText(sendIntent.getCharSequenceExtra(Intent.EXTRA_TEXT));
            }

            else if (ImageMessage.supportsMimeType(mime)) {
                // send image immediately
                sendImageMessage((Uri) sendIntent.getParcelableExtra(Intent.EXTRA_STREAM), mime);
            }
            else {
                Log.e(TAG, "mime " + mime + " not supported");
            }

            sendIntent = null;
        }
    }

    private void processIntent(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            Log.w(TAG, "restoring from saved instance");
            userName = savedInstanceState.getString(MESSAGE_THREAD_USERNAME);
            if (userName == null)
                userName = userId;
            userPhone = savedInstanceState.getString(MESSAGE_THREAD_USERPHONE);
            userId = savedInstanceState.getString(MESSAGE_THREAD_PEER);
            threadId = savedInstanceState.getLong(MESSAGE_THREAD_ID, -1);
            return;
        }

        Intent intent = getIntent();
        if (intent != null) {
            final String action = intent.getAction();

            // view intent
            if (Intent.ACTION_VIEW.equals(action)) {
                Uri uri = intent.getData();
                Log.w(TAG, "intent uri: " + uri);
                ContentResolver cres = getContentResolver();

                Cursor c = cres.query(uri, new String[] {
                        ContactsContract.Data.DATA1,
                        ContactsContract.Data.DATA3
                        }, null, null, null);
                if (c.moveToFirst()) {
                    userName = c.getString(0);
                    userPhone = c.getString(1);

                    // FIXME should it be retrieved from RawContacts.SYNC3 ??
                    try {
                        userId = MessageUtils.sha1(userPhone);
                    }
                    catch (Exception e) {
                        Log.e(TAG, "sha1 digest failed", e);
                    }

                    Cursor cp = cres.query(Messages.CONTENT_URI,
                            new String[] { Messages.THREAD_ID },
                            Messages.PEER + " = ?", new String[] { userId }, null);
                    if (cp.moveToFirst())
                        threadId = cp.getLong(0);
                    cp.close();
                }
                c.close();
            }

            // send external content
            else if (Intent.ACTION_SEND.equals(action)) {
                sendIntent = intent;
                String mime = intent.getType();

                Log.i(TAG, "sending data to someone: " + mime);
                chooseContact();

                // don't do other things - onActivityResult will handle the rest
                return;
            }

            // view conversation - just threadId provided
            else if (ACTION_VIEW_CONVERSATION.equals((action))) {
                threadId = intent.getLongExtra(MESSAGE_THREAD_ID, -1);
                mConversation = Conversation.loadFromId(this, threadId);

                userId = mConversation.getRecipient();
                Contact contact = mConversation.getContact();
                if (contact != null) {
                    userName = contact.getName();
                    userPhone = contact.getNumber();
                }
                else {
                    userName = userId;
                }
            }

            // private launch intent
            else {
                userName = intent.getStringExtra(MESSAGE_THREAD_USERNAME);
                if (userName == null)
                    userName = userId;
                userPhone = intent.getStringExtra(MESSAGE_THREAD_USERPHONE);
                userId = intent.getStringExtra(MESSAGE_THREAD_PEER);
                threadId = intent.getLongExtra(MESSAGE_THREAD_ID, -1);
            }
        }
    }

    private void processStart() {
        // opening for contact picker - do nothing
        if (threadId < 0 && sendIntent != null) return;

        Log.i(TAG, "starting query with threadId " + threadId);
        if (threadId > 0) {
            startQuery(true);
        }
        else {
            mConversation = Conversation.createNew(this);
            mConversation.setRecipient(userId);
        }

        String title = userName;
        if (userPhone != null)
            title += " <" + userPhone + ">";
        setTitle(title);

        // did we have a SEND action message to be sent?
        processSendIntent();
    }

    @Override
    protected void onStart() {
        super.onStart();

        // focus text entry
        mTextEntry.requestFocus();

        processStart();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CONTACT_PICKER) {
            if (resultCode == RESULT_OK) {
                Uri contact = Contacts.lookupContact(getContentResolver(), data.getData());
                if (contact != null) {
                    Log.i(TAG, "composing message for contact: " + contact);
                    Intent i = fromContactPicker(this, contact);
                    if (i != null) {
                        onNewIntent(i);
                    }
                    else
                        Toast.makeText(this, "Contact seems not to be registered on Nuntius.", Toast.LENGTH_LONG)
                            .show();
                }
            }
            // nothing to do - exit
            else {
                Log.w(TAG, "unknown request code " + requestCode);
                finish();
            }
        }
    }

    public static Intent fromContactPicker(Context context, Uri contactUri) {
        String userId = Contact.getUserId(context, contactUri);
        if (userId != null) {
            Conversation conv = Conversation.loadFromUserId(context, userId);
            // not found - create new
            if (conv == null) {
                conv = Conversation.createNew(context);
                conv.setRecipient(userId);
            }

            return fromConversation(context, conv);
        }

        return null;
    }

    /**
     * Creates an {@link Intent} for launching the composer for a given {@link Conversation}.
     * @param context
     * @param conv
     * @return
     */
    public static Intent fromConversation(Context context, Conversation conv) {
        Intent i = new Intent(context, ComposeMessage.class);
        i.putExtra(ComposeMessage.MESSAGE_THREAD_ID, conv.getThreadId());
        i.putExtra(ComposeMessage.MESSAGE_THREAD_PEER, conv.getRecipient());
        Contact contact = conv.getContact();
        if (contact != null) {
            i.putExtra(ComposeMessage.MESSAGE_THREAD_USERNAME, contact.getName());
            i.putExtra(ComposeMessage.MESSAGE_THREAD_USERPHONE, contact.getNumber());
        }
        return i;
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    /** Prevents the list adapter from using the cursor (which is being destroyed). */
    @Override
    protected void onStop() {
        super.onStop();
        mListAdapter.changeCursor(null);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        setIntent(intent);
        processIntent(null);
    }

    @Override
    protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
        processIntent(state);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        out.putLong(ComposeMessage.MESSAGE_THREAD_ID, threadId);
        out.putString(ComposeMessage.MESSAGE_THREAD_PEER, userId);
        if (mConversation != null) {
            Contact contact = mConversation.getContact();
            if (contact != null) {
                out.putString(ComposeMessage.MESSAGE_THREAD_USERNAME, contact.getName());
                out.putString(ComposeMessage.MESSAGE_THREAD_USERPHONE, contact.getNumber());
            }
        }

        super.onSaveInstanceState(out);
    }

    /**
     * The conversation list query handler.
     */
    private final class MessageListQueryHandler extends AsyncQueryHandler {
        public MessageListQueryHandler(ContentResolver contentResolver) {
            super(contentResolver);
        }

        @Override
        protected synchronized void onQueryComplete(int token, Object cookie, Cursor cursor) {
            switch (token) {
                case MESSAGE_LIST_QUERY_TOKEN:
                    mListAdapter.changeCursor(cursor);
                    setProgressBarIndeterminateVisibility(false);

                    // no messages to show - exit
                    if (mListAdapter.getCount() == 0) {
                        Log.w(TAG, "no data to view - exit");
                        finish();
                    }

                    break;

                case CONVERSATION_QUERY_TOKEN:
                    Log.i(TAG, "conversation query completed, marking as read");
                    if (cursor.moveToFirst()) {
                        mConversation = Conversation.createFromCursor(ComposeMessage.this, cursor);
                        // mark all messages as read
                        mConversation.markAsRead();
                    }

                    cursor.close();

                    break;

                default:
                    Log.e(TAG, "onQueryComplete called with unknown token " + token);
            }
        }
    }
}
