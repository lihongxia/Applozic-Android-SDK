package com.applozic.mobicomkit.api.conversation;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

import com.applozic.mobicomkit.api.attachment.FileClientService;
import com.applozic.mobicomkit.api.attachment.FileMeta;
import com.applozic.mobicommons.file.FileUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.applozic.mobicomkit.api.MobiComKitConstants;
import com.applozic.mobicomkit.api.account.user.MobiComUserPreference;
import com.applozic.mobicomkit.api.conversation.database.MessageDatabaseService;
import com.applozic.mobicomkit.broadcast.BroadcastService;

import com.applozic.mobicommons.json.AnnotationExclusionStrategy;
import com.applozic.mobicommons.json.ArrayAdapterFactory;
import com.applozic.mobicommons.json.GsonUtils;
import com.applozic.mobicommons.people.contact.Contact;
import com.applozic.mobicommons.people.group.Group;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

public class MobiComConversationService {

    private static final String TAG = "Conversation";

    protected Context context = null;
    protected MessageClientService messageClientService;
    protected MessageDatabaseService messageDatabaseService;

    public MobiComConversationService(Context context) {
        this.context = context;
        this.messageClientService = new MessageClientService(context);
        this.messageDatabaseService = new MessageDatabaseService(context);
    }

    public void sendMessage(Message message) {
        sendMessage(message, MessageIntentService.class);
    }

    public void sendMessage(Message message, Class messageIntentClass) {
        Intent intent = new Intent(context, messageIntentClass);
        intent.putExtra(MobiComKitConstants.MESSAGE_JSON_INTENT, GsonUtils.getJsonFromObject(message, Message.class));
        context.startService(intent);
    }

    public List<Message> getLatestMessagesGroupByPeople() {
        return getLatestMessagesGroupByPeople(null);
    }

    public synchronized List<Message> getLatestMessagesGroupByPeople(Long createdAt) {
        boolean emptyTable = messageDatabaseService.isMessageTableEmpty();

        if (emptyTable) {
            getMessages(null, null, null, null);
        }

        List<Message> messageList = messageDatabaseService.getMessages(createdAt);
        Iterator<Message> messageIterator = messageList.iterator();
        while (messageIterator.hasNext()) {
            Message message = messageIterator.next();
            if (message.isSentToMany()) {
                messageIterator.remove();
            }
        }

        return messageList;
    }

    public List<Message> getMessages(String userId, Long startTime, Long endTime) {
        return getMessages(startTime, endTime, new Contact(userId), null);
    }

    public synchronized List<Message> getMessages(Long startTime, Long endTime, Contact contact, Group group) {
        List<Message> messageList = new ArrayList<Message>();
        List<Message> cachedMessageList = messageDatabaseService.getMessages(startTime, endTime, contact, group);

        if (!cachedMessageList.isEmpty() &&
                ((cachedMessageList.size() > 1) || ((cachedMessageList.size() > 1) && !cachedMessageList.get(0).isLocalMessage()))) {
            Log.i(TAG,"cachedMessageList size is : "+cachedMessageList.size());
            return cachedMessageList;
        }

        String data;
        try {
            data = messageClientService.getMessages(contact, group, startTime, endTime);
            Log.i(TAG, "Received response from server for Messages: " + data);
        } catch (Exception ex) {
            ex.printStackTrace();
            return cachedMessageList;
        }

        if (data == null || TextUtils.isEmpty(data) || data.equals("UnAuthorized Access") || !data.contains("{")) {
            //Note: currently not supporting syncing old group messages from server
            if (group != null && group.getGroupId() != null) {
                return cachedMessageList;
            }
            return cachedMessageList;
        }

        try {
            Gson gson = new GsonBuilder().registerTypeAdapterFactory(new ArrayAdapterFactory())
                    .setExclusionStrategies(new AnnotationExclusionStrategy()).create();
            JsonParser parser = new JsonParser();
            String element = parser.parse(data).getAsJsonObject().get("message").toString();
            Message[] messages = gson.fromJson(element, Message[].class);
            MobiComUserPreference userPreferences = MobiComUserPreference.getInstance(context);

            if (messages != null && messages.length > 0 && cachedMessageList.size() > 0 && cachedMessageList.get(0).isLocalMessage()) {
                if (cachedMessageList.get(0).equals(messages[0])) {
                    Log.i(TAG, "Both messages are same.");
                    deleteMessage(cachedMessageList.get(0));
                }
            }

           new MobiComMessageService(context,MessageIntentService.class).processContactFromMessages(Arrays.asList(messages));
            for (Message message : messages) {
                if (!message.isCall() || userPreferences.isDisplayCallRecordEnable()) {
                    //TODO: remove this check..right now in some cases it is coming as null.
                    // we have to figure out if it is a parsing problem or response from server.
                    if (message.getTo() == null) {
                        continue;
                    }
                    if(message.hasAttachment()){
                        setFilePathifExist(message);
                    }
                    messageList.add(message);
                    messageDatabaseService.createMessage(message);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        messageList.removeAll(cachedMessageList);
        messageList.addAll(cachedMessageList);

        Collections.sort(messageList, new Comparator<Message>() {
            @Override
            public int compare(Message lhs, Message rhs) {
                return lhs.getCreatedAtTime().compareTo(rhs.getCreatedAtTime());
            }
        });
        return messageList;
    }

    private void setFilePathifExist(Message message) {
        FileMeta fileMeta = message.getFileMetas();
        File file = FileClientService.getFilePath(fileMeta.getBlobKeyString() + "." + FileUtils.getFileFormat(fileMeta.getName()), context, fileMeta.getContentType());
        if(file.exists()){
            ArrayList<String> arrayList = new ArrayList<String>();
            arrayList.add(file.getAbsolutePath());
            message.setFilePaths(arrayList);
        }
    }

    public boolean deleteMessage(Message message, Contact contact) {
        if (!message.isSentToServer()) {
            deleteMessageFromDevice(message, contact != null ? contact.getContactIds() : null);
            return true;
        }
        String response = messageClientService.deleteMessage(message, contact);
        if ("success".equals(response)) {
            deleteMessageFromDevice(message, contact != null ? contact.getContactIds() : null);
        } else {
            messageDatabaseService.updateDeleteSyncStatus(message, "1");
        }
        return true;
    }

    public boolean deleteMessage(Message message) {
        return deleteMessage(message, null);
    }

    public String deleteMessageFromDevice(Message message, String contactNumber) {
        if (message == null) {
            return null;
        }
        return messageDatabaseService.deleteMessage(message, contactNumber);
    }

    public void deleteConversationFromDevice(String contactNumber) {
        messageDatabaseService.deleteConversation(contactNumber);
    }

    public void deleteAndBroadCast(final Contact contact, boolean deleteFromServer) {
        deleteConversationFromDevice(contact.getContactIds());
        if (deleteFromServer) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    messageClientService.deleteConversationThreadFromServer(contact);
                }
            }).start();
        }
        BroadcastService.sendConversationDeleteBroadcast(context, BroadcastService.INTENT_ACTIONS.DELETE_CONVERSATION.toString(), contact.getContactIds(),"success");
    }

    public void deleteSync(final Contact contact) {
        String response = messageClientService.syncDeleteConversationThreadFromServer(contact);
        if ("success".equals(response)) {
            messageDatabaseService.deleteConversation(contact.getContactIds());
        }
        BroadcastService.sendConversationDeleteBroadcast(context, BroadcastService.INTENT_ACTIONS.DELETE_CONVERSATION.toString(),
                contact.getContactIds(), response);
    }
    public String deleteMessageFromDevice(String keyString, String contactNumber) {
        return deleteMessageFromDevice(messageDatabaseService.getMessage(keyString), contactNumber);
    }

//    public void addFileMetaDetails(String responseString, Message message) {
//        JsonParser jsonParser = new JsonParser();
//        List<FileMeta> metaFileList = new ArrayList<FileMeta>();
//        JsonObject jsonObject = jsonParser.parse(responseString).getAsJsonObject();
//        if (jsonObject.has("fileMetas")) {
//            Gson gson = new Gson();
//            metaFileList.add(gson.fromJson(jsonObject.get("fileMetas"), FileMeta.class));
//        }
//        message.setFileMetas(metaFileList);
//    }

}