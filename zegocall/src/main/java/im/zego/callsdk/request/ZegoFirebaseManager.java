package im.zego.callsdk.request;

import android.text.TextUtils;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.gms.tasks.Continuation;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuth.AuthStateListener;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.FirebaseFunctionsException;
import com.google.firebase.functions.HttpsCallableResult;
import im.zego.callsdk.callback.ZegoRequestCallback;
import im.zego.callsdk.command.ZegoRequestProtocol;
import im.zego.callsdk.core.commands.ZegoCommand;
import im.zego.callsdk.listener.ZegoListenerManager;
import im.zego.callsdk.listener.ZegoListenerUpdater;
import im.zego.callsdk.model.DatabaseCall;
import im.zego.callsdk.model.DatabaseCall.DatabaseCallUser;
import im.zego.callsdk.model.DatabaseCall.Status;
import im.zego.callsdk.model.ZegoCallType;
import im.zego.callsdk.model.ZegoDeclineType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ZegoFirebaseManager implements ZegoRequestProtocol {

    private ZegoListenerUpdater updater;
    private static final String TAG = "ZegoFirebaseManager";
    private Map<String, ValueEventListener> databaseListenerMap = new HashMap<>();
    private ChildEventListener callEventListener;

    /**
     * current call relative to self
     */
    private List<DatabaseCall> selfCalls = new ArrayList<>();

    public ZegoFirebaseManager() {
        Log.d(TAG, "ZegoFirebaseManager() called");
        updater = ZegoListenerManager.getInstance();
        FirebaseDatabase database = FirebaseDatabase.getInstance();
        database.setPersistenceEnabled(true);
        FirebaseAuth.getInstance().addAuthStateListener(new AuthStateListener() {
            @Override
            public void onAuthStateChanged(@NonNull FirebaseAuth firebaseAuth) {
                FirebaseUser currentUser = firebaseAuth.getCurrentUser();
                Log.d(TAG,
                    "onAuthStateChanged() called with: currentUser = [" + currentUser + "]");
                clearCallData();
                if (currentUser != null) {
                    if (callEventListener == null) {
                        callEventListener = listenUserCall();
                    }
                    Log.d(TAG, "add call listener");
                    database.getReference("/call").addChildEventListener(callEventListener);
                } else {
                    if (callEventListener != null) {
                        Log.d(TAG, "remove call listener");
                        database.getReference("/call").removeEventListener(callEventListener);
                    }
                }
            }
        });
    }

    @Override
    public void request(String path, Map<String, Object> parameter, ZegoRequestCallback callback) {
        Log.d(TAG,
            "request() called with: path = [" + path + "], parameter = [" + parameter + "], callback = [" + callback
                + "]");
        if (ZegoCommand.START_CALL.equals(path)) {
            startCallUser(parameter, callback);
        } else if (ZegoCommand.END_CALL.equals(path)) {
            endCallUser(parameter, callback);
        } else if (ZegoCommand.ACCEPT_CALL.equals(path)) {
            acceptUserCall(parameter, callback);
        } else if (ZegoCommand.DECLINE_CALL.equals(path)) {
            declineUserCall(parameter, callback);
        } else if (ZegoCommand.CANCEL_CALL.equals(path)) {
            cancelUserCall(parameter, callback);
        } else if (ZegoCommand.GET_TOKEN.equals(path)) {
            getTokenFromCloudFunction(parameter, callback);
        } else if (ZegoCommand.HEARTBEAT.equals(path)) {
            sendHeartBeat(parameter, callback);
        }
    }

    private void getTokenFromCloudFunction(Map<String, Object> parameter,
        ZegoRequestCallback callback) {
        Log.d(TAG,
            "getTokenFromCloudFunction() called with: parameter = [" + parameter + "], callback = [" + callback + "]");
        String userID = (String) parameter.get("userID");
        Long time = (Long) parameter.get("effectiveTime");
        Map<String, Object> data = new HashMap<>();
        data.put("id", userID);
        data.put("effective_time", time);

        FirebaseFunctions.getInstance().getHttpsCallable("getToken")
            .call(data)
            .continueWith(new Continuation<HttpsCallableResult, Object>() {
                @Override
                public Object then(@NonNull Task<HttpsCallableResult> task) throws Exception {
                    return task.getResult().getData();
                }
            })
            .addOnCompleteListener(new OnCompleteListener<Object>() {
                @Override
                public void onComplete(@NonNull Task<Object> task) {
                    if (!task.isSuccessful()) {
                        Exception e = task.getException();
                        if (e instanceof FirebaseFunctionsException) {
                            FirebaseFunctionsException ffe = (FirebaseFunctionsException) e;
                            FirebaseFunctionsException.Code code = ffe.getCode();
                            Object details = ffe.getDetails();
                        }
                        if (callback != null) {
                            callback.onResult(-1000, e);
                        }
                        return;
                    }
                    HashMap<String, String> result = (HashMap<String, String>) task.getResult();
                    if (callback != null) {
                        callback.onResult(0, result.get("token"));
                    }
                }
            });
    }

    private void sendHeartBeat(Map<String, Object> parameter, ZegoRequestCallback callback) {
        Log.d(TAG, "sendHeartBeat() called with: parameter = [" + parameter + "], callback = [" + callback + "]");
        String callID = (String) parameter.get("callID");
        String userID = (String) parameter.get("userID");
        Map<String, Object> callUpdates = new HashMap<>();
        callUpdates.put("/users/" + userID + "/heartbeat_time", ServerValue.TIMESTAMP);
        FirebaseDatabase database = FirebaseDatabase.getInstance();
        DatabaseReference callRef = database.getReference("call").child(callID);
        callRef.updateChildren(callUpdates).addOnSuccessListener(new OnSuccessListener<Void>() {
            @Override
            public void onSuccess(Void unused) {
            }
        }).addOnFailureListener(new OnFailureListener() {
            @Override
            public void onFailure(@NonNull Exception e) {
            }
        });
    }

    private void startCallUser(Map<String, Object> parameter, ZegoRequestCallback callback) {
        Log.d(TAG, "startCallUser() called with: parameter = [" + parameter + "], callback = [" + callback + "]");
        ZegoCallType callType = (ZegoCallType) parameter.get("callType");
        HashMap<String, String> caller = (HashMap<String, String>) parameter.get("caller");
        String callID = (String) parameter.get("callID");

        Map<String, DatabaseCallUser> users = new HashMap<>();
        DatabaseCallUser callUser = new DatabaseCallUser();
        callUser.user_id = caller.get("id");
        callUser.user_name = caller.get("name");
        callUser.caller_id = caller.get("id");
        long currentTimeMillis = System.currentTimeMillis();
        callUser.start_time = currentTimeMillis;
        callUser.status = Status.WAIT.getValue();
        users.put(callUser.user_id, callUser);

        List<HashMap<String, String>> list = (List<HashMap<String, String>>) parameter.get("callees");
        for (HashMap<String, String> hashMap : list) {
            String id = hashMap.get("id");
            String name = hashMap.get("name");
            DatabaseCallUser targetUser = new DatabaseCallUser();
            targetUser.user_id = id;
            targetUser.user_name = name;
            targetUser.caller_id = caller.get("id");
            targetUser.start_time = currentTimeMillis;
            targetUser.status = Status.WAIT.getValue();
            users.put(targetUser.user_id, targetUser);
        }

        DatabaseCall databaseCall = new DatabaseCall();
        databaseCall.call_id = callID;
        databaseCall.call_type = callType.getValue();
        databaseCall.users = users;
        databaseCall.call_status = Status.WAIT.getValue();

        FirebaseDatabase database = FirebaseDatabase.getInstance();
        DatabaseReference callRef = database.getReference("call").child(callID);
        callRef.setValue(databaseCall).addOnSuccessListener(new OnSuccessListener<Void>() {
            @Override
            public void onSuccess(Void unused) {
                Log.d(TAG, "startCallUser onSuccess() called with: unused = [" + unused + "]");
                addCallListener(databaseCall);

                if (callback != null) {
                    callback.onResult(0, null);
                }
            }
        }).addOnFailureListener(new OnFailureListener() {
            @Override
            public void onFailure(@NonNull Exception e) {
                Log.d(TAG, "startCallUser onFailure() called with: e = [" + e + "]");
                if (callback != null) {
                    callback.onResult(-1000, null);
                }
                removeCallListener(databaseCall.call_id);
            }
        });
        setCurrentCallData(databaseCall);
    }

    private boolean isCallIDContainsSelf(DataSnapshot snapshot) {
        FirebaseUser currentUser = getCurrentUser();
        DatabaseCall databaseCall = snapshot.getValue(DatabaseCall.class);
        boolean result = false;
        if (databaseCall.users != null) {
            for (DatabaseCallUser value : databaseCall.users.values()) {
                if (Objects.equals(currentUser.getUid(), value.user_id)) {
                    result = true;
                    break;
                }
            }
        }
        return result;
    }

    private ChildEventListener listenUserCall() {
        return new ChildEventListener() {
            @Override
            public void onChildAdded(@NonNull DataSnapshot snapshot, @Nullable String previousChildName) {
                FirebaseUser currentUser = getCurrentUser();
                if (currentUser == null) {
                    return;
                }
                Log.d(TAG, "onChildAdded() called with: snapshot = [" + snapshot + "], previousChildName = ["
                    + previousChildName + "]");
                DatabaseCall databaseCall = snapshot.getValue(DatabaseCall.class);
                if (databaseCall.call_status == 0 || !isCallIDContainsSelf(snapshot)) {
                    return;
                }
                DatabaseCallUser caller = new DatabaseCallUser();
                DatabaseCallUser receiver = new DatabaseCallUser();
                for (DatabaseCallUser user : databaseCall.users.values()) {
                    if (!TextUtils.isEmpty(user.user_id)) {
                        if (Objects.equals(user.user_id, user.caller_id)) {
                            caller = user;
                        } else {
                            receiver = user;
                        }
                    }
                }
                boolean isSelfCaller = Objects.equals(currentUser.getUid(), caller.user_id);
                if (isSelfCaller) {
                    return;
                }
                if (isCurrentIdle()) {
                    addCallListener(databaseCall);
                } else {
                    declineCallInner(currentUser.getUid(), caller.caller_id,
                        databaseCall.call_id, ZegoDeclineType.Busy.getValue(), null);
                }
            }

            @Override
            public void onChildChanged(@NonNull DataSnapshot snapshot, @Nullable String previousChildName) {
            }

            @Override
            public void onChildRemoved(@NonNull DataSnapshot snapshot) {
                DatabaseCall databaseCall = snapshot.getValue(DatabaseCall.class);
                if (databaseCall != null) {
                    Log.d(TAG, "onChildRemoved() called with: databaseCall.call_id = [" + databaseCall.call_id + "]");
                    removeCallListener(databaseCall.call_id);
                }
            }

            @Override
            public void onChildMoved(@NonNull DataSnapshot snapshot, @Nullable String previousChildName) {

            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {

            }
        };
    }

    private void addCallListener(DatabaseCall databaseCall) {
        String callID = databaseCall.call_id;
        Log.d(TAG, "addCallListener() called with: callID = [" + callID + "]");
        if (databaseListenerMap.containsKey("call/" + callID)) {
            return;
        }
        ValueEventListener listener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Log.d(TAG, "onDataChange() called with: snapshot = [" + snapshot + "]");
                if (snapshot.getValue() == null) { //  is already deleted
                    processCallIDRemoved(callID);
                    return;
                }
                DatabaseCall previousCall = getCurrentCallData();
                DatabaseCall changedValue = snapshot.getValue(DatabaseCall.class);
                if (changedValue == null || changedValue.call_status == 0) {
                    // 0 means set heartbeat to removed call
                    return;
                }
                if (previousCall != null && !Objects.equals(previousCall.call_id, changedValue.call_id)) {
                    removeCallListener(previousCall.call_id);
                }
                setCurrentCallData(changedValue);

                DatabaseCallUser caller = new DatabaseCallUser();
                DatabaseCallUser receiver = new DatabaseCallUser();
                for (DatabaseCallUser user : changedValue.users.values()) {
                    if (TextUtils.isEmpty(user.user_id)) {
                        return;
                    }
                    String targetUserID = user.user_id;
                    String callerUserID = user.caller_id;
                    if (Objects.equals(callerUserID, targetUserID)) {
                        caller = user;
                    } else {
                        receiver = user;
                    }
                }
                FirebaseUser currentUser = getCurrentUser();
                boolean isSelfCaller = Objects.equals(currentUser.getUid(), caller.user_id);
                DatabaseCallUser self = isSelfCaller ? caller : receiver;
                DatabaseCallUser other = isSelfCaller ? receiver : caller;

                if (previousCall != null) {
                    boolean callStatusChanged = (previousCall.call_status != changedValue.call_status);
                    boolean callerStatusChanged = (previousCall.users.get(caller.user_id).status
                        != changedValue.users.get(caller.user_id).status);
                    boolean receiverStatusChanged = (previousCall.users.get(receiver.user_id).status
                        != changedValue.users.get(receiver.user_id).status);
                    if (!callStatusChanged && !callerStatusChanged && !receiverStatusChanged) {
                        // no status changed , is heartbeat update
                        if (caller.heartbeat_time != 0 && receiver.heartbeat_time != 0) {
                            if (Math.abs(caller.heartbeat_time - receiver.heartbeat_time) > 60_000) {
                                HashMap<String, String> data = new HashMap<>();
                                // i receive,means the other one timeout
                                data.put("user_id", other.user_id);
                                data.put("call_id", callID);
                                updater.receiveUpdate(ZegoListenerManager.TIMEOUT_CALL, data);
                                removeCallListener(callID);
                            }
                        }
                        return;
                    }
                }
                if (changedValue.call_status == Status.FINISHED.getValue()) {
                    if (caller.status == Status.FINISHED.getValue()
                        || receiver.status == Status.FINISHED.getValue()) {
                        HashMap<String, String> data = new HashMap<>();
                        // i receive,means the other one is the operator,because
                        // the operator has already removed call listener
                        data.put("id", other.user_id);
                        data.put("call_id", callID);
                        updater.receiveUpdate(ZegoListenerManager.END_CALL, data);
                        removeCallListener(callID);
                    } else if (Status.DECLINED.getValue() == caller.status) {
                        HashMap<String, String> data = new HashMap<>();
                        data.put("callee_id", receiver.user_id);
                        data.put("call_id", callID);
                        data.put("type", "1");
                        updater.receiveUpdate(ZegoListenerManager.DECLINE_CALL, data);
                    } else if (Status.BUSY.getValue() == caller.status) {
                        HashMap<String, String> data = new HashMap<>();
                        data.put("callee_id", receiver.user_id);
                        data.put("call_id", callID);
                        data.put("type", "2");
                        updater.receiveUpdate(ZegoListenerManager.DECLINE_CALL, data);
                    } else if (Status.CANCELED.getValue() == caller.status) {
                        HashMap<String, String> data = new HashMap<>();
                        data.put("caller_id", caller.user_id);
                        data.put("callee_id", receiver.user_id);
                        data.put("call_id", callID);
                        updater.receiveUpdate(ZegoListenerManager.CANCEL_CALL, data);
                    }
                    removeCallListener(callID);
                } else {
                    if (receiver.status == Status.CONNECTED.getValue()) {
                        HashMap<String, String> data = new HashMap<>();
                        data.put("callee_id", receiver.user_id);
                        data.put("call_id", callID);
                        updater.receiveUpdate(ZegoListenerManager.ACCEPT_CALL, data);
                    } else if (receiver.status == Status.WAIT.getValue()) {
                        HashMap<String, Object> data = new HashMap<>();
                        HashMap<String, String> callerData = new HashMap<>();
                        callerData.put("id", caller.user_id);
                        callerData.put("name", caller.user_name);
                        data.put("caller", callerData);
                        List<HashMap<String, String>> calleeData = new ArrayList<>();
                        HashMap<String, String> callee = new HashMap<>();
                        callee.put("id", receiver.user_id);
                        callee.put("name", receiver.user_name);
                        calleeData.add(callee);
                        data.put("callees", calleeData);
                        data.put("call_id", callID);
                        data.put("type", changedValue.call_type);
                        updater.receiveUpdate(ZegoListenerManager.RECEIVE_CALL, data);
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {

            }
        };
        addDatabaseListener("call/" + callID, listener);
    }

    private void processCallIDRemoved(String callID) {
        if (getCurrentCallData() != null && isCurrentCall(callID)) {
            DatabaseCallUser caller = new DatabaseCallUser();
            DatabaseCallUser receiver = new DatabaseCallUser();
            for (DatabaseCallUser user : getCurrentCallData().users.values()) {
                if (TextUtils.isEmpty(user.user_id)) {
                    return;
                }
                String targetUserID = user.user_id;
                String callerUserID = user.caller_id;
                if (Objects.equals(callerUserID, targetUserID)) {
                    caller = user;
                } else {
                    receiver = user;
                }
            }
            FirebaseUser currentUser = getCurrentUser();
            boolean isSelfCaller = Objects.equals(currentUser.getUid(), caller.user_id);
            DatabaseCallUser self = isSelfCaller ? caller : receiver;
            DatabaseCallUser other = isSelfCaller ? receiver : caller;
            HashMap<String, String> data = new HashMap<>();
            if (getCurrentCallData().call_status == 1) {
                if (isSelfCaller) {
                    data.put("callee_id", receiver.user_id);
                    data.put("call_id", callID);
                    data.put("type", "1");
                    updater.receiveUpdate(ZegoListenerManager.DECLINE_CALL, data);
                } else {
                    data.put("caller_id", caller.user_id);
                    data.put("callee_id", receiver.user_id);
                    data.put("call_id", callID);
                    updater.receiveUpdate(ZegoListenerManager.CANCEL_CALL, data);
                }
            } else {
                data.put("id", other.user_id);
                data.put("call_id", callID);
                updater.receiveUpdate(ZegoListenerManager.END_CALL, data);
            }
        }
        removeCallListener(callID);
    }

    private FirebaseUser getCurrentUser() {
        return FirebaseAuth.getInstance().getCurrentUser();
    }


    private void removeCallListener(String callID) {
        Log.d(TAG, "removeCallListener() called with: callID = [" + callID + "]");
        removeDatabaseListener("call/" + callID);
        removeCallData(callID);
    }

    private void acceptUserCall(Map<String, Object> parameter, ZegoRequestCallback callback) {
        Log.d(TAG, "acceptUserCall() called with: parameter = [" + parameter + "], callback = [" + callback + "]");
        String selfUserID = (String) parameter.get("selfUserID");
        String callerID = (String) parameter.get("userID");
        String callID = (String) parameter.get("callID");

        Map<String, Object> callUpdates = new HashMap<>();
        callUpdates.put("/call_status", Status.CONNECTED.getValue());
        callUpdates.put("/users/" + selfUserID + "/status", Status.CONNECTED.getValue());
        callUpdates.put("/users/" + callerID + "/status", Status.CONNECTED.getValue());
        callUpdates.put("/users/" + selfUserID + "/connected_time", ServerValue.TIMESTAMP);
        callUpdates.put("/users/" + callerID + "/connected_time", ServerValue.TIMESTAMP);

        FirebaseDatabase database = FirebaseDatabase.getInstance();
        DatabaseReference callRef = database.getReference("call").child(callID);
        callRef.updateChildren(callUpdates).addOnSuccessListener(new OnSuccessListener<Void>() {
            @Override
            public void onSuccess(Void unused) {
                Log.d(TAG, "acceptUserCall onSuccess() called with: unused = [" + unused + "]");
                if (callback != null) {
                    callback.onResult(0, callID);
                }
            }
        }).addOnFailureListener(new OnFailureListener() {
            @Override
            public void onFailure(@NonNull Exception e) {
                Log.d(TAG, "acceptUserCall onFailure() called with: e = [" + e + "]");
                if (callback != null) {
                    callback.onResult(-1000, null);
                }
            }
        });
    }

    private void declineUserCall(Map<String, Object> parameter, ZegoRequestCallback callback) {
        Log.d(TAG, "declineUserCall() called with: parameter = [" + parameter + "], callback = [" + callback + "]");
        String selfUserID = (String) parameter.get("selfUserID");
        String callerID = (String) parameter.get("userID");
        String callID = (String) parameter.get("callID");
        int type = (int) parameter.get("type");

        declineCallInner(selfUserID, callerID, callID, type, callback);
    }

    private void declineCallInner(String selfUserID, String callerID, String callID, int type,
        ZegoRequestCallback callback) {
        Log.d(TAG, "declineCallInner() called with: selfUserID = [" + selfUserID + "], callerID = [" + callerID
            + "], callID = [" + callID + "], type = [" + type + "], callback = [" + callback + "]");
        int value;
        if (type == ZegoDeclineType.Decline.getValue()) {
            value = Status.DECLINED.getValue();
        } else {
            value = Status.BUSY.getValue();
        }
        Map<String, Object> callUpdates = new HashMap<>();
        callUpdates.put("/call_status", Status.FINISHED.getValue());
        callUpdates.put("/users/" + selfUserID + "/status", value);
        callUpdates.put("/users/" + callerID + "/status", value);
        FirebaseDatabase database = FirebaseDatabase.getInstance();
        DatabaseReference callRef = database.getReference("call").child(callID);
        callRef.updateChildren(callUpdates)
            .addOnSuccessListener(new OnSuccessListener<Void>() {
                @Override
                public void onSuccess(Void unused) {
                    Log.d(TAG, "declineCallInner onSuccess() called with: unused = [" + unused + "]");
                    if (callback != null) {
                        callback.onResult(0, callID);
                    }

                }
            })
            .addOnFailureListener(new OnFailureListener() {
                @Override
                public void onFailure(@NonNull Exception e) {
                    Log.d(TAG, "declineCallInner onFailure() called with: e = [" + e + "]");
                    if (callback != null) {
                        callback.onResult(-1000, null);
                    }
                }
            });
        removeCallListener(callID);
    }

    private void cancelUserCall(Map<String, Object> parameter, ZegoRequestCallback callback) {
        Log.d(TAG, "cancelUserCall() called with: parameter = [" + parameter + "], callback = [" + callback + "]");
        String beCanceledUserID = (String) parameter.get("userID");
        String selfUserID = (String) parameter.get("selfUserID");
        String callerID = selfUserID;
        String callID = (String) parameter.get("callID");

        Map<String, Object> callUpdates = new HashMap<>();
        callUpdates.put("/call_status", Status.FINISHED.getValue());
        callUpdates.put("/users/" + beCanceledUserID + "/status", Status.CANCELED.getValue());
        callUpdates.put("/users/" + callerID + "/status", Status.CANCELED.getValue());
        FirebaseDatabase database = FirebaseDatabase.getInstance();
        DatabaseReference callRef = database.getReference("call").child(callID);
        callRef.updateChildren(callUpdates).addOnSuccessListener(new OnSuccessListener<Void>() {
            @Override
            public void onSuccess(Void unused) {
                Log.d(TAG, "cancelUserCall onSuccess() called with: unused = [" + unused + "]");
                if (callback != null) {
                    callback.onResult(0, callID);
                }
            }
        }).addOnFailureListener(new OnFailureListener() {
            @Override
            public void onFailure(@NonNull Exception e) {
                Log.d(TAG, "cancelUserCall onFailure() called with: e = [" + e + "]");
                if (callback != null) {
                    callback.onResult(-1000, null);
                }
            }
        });
        removeCallListener(callID);
    }

    private void endCallUser(Map<String, Object> parameter, ZegoRequestCallback callback) {
        Log.d(TAG, "endCallUser() called with: parameter = [" + parameter + "], callback = [" + callback + "]");
        String selfUserID = (String) parameter.get("selfUserID");
        String callerID = selfUserID;
        String callID = (String) parameter.get("callID");

        Map<String, Object> callUpdates = new HashMap<>();
        callUpdates.put("/call_status", Status.FINISHED.getValue());
        if (getCurrentCallData() != null) {
            if (Objects.equals(getCurrentCallData().call_id, callID)) {
                Collection<DatabaseCallUser> values = getCurrentCallData().users.values();
                for (DatabaseCallUser value : values) {
                    callUpdates.put("/users/" + value.user_id + "/status", Status.FINISHED.getValue());
                    callUpdates.put("/users/" + value.user_id + "/finish_time", ServerValue.TIMESTAMP);
                }
            } else {
                callUpdates.put("/users/" + selfUserID + "/status", Status.FINISHED.getValue());
                callUpdates.put("/users/" + selfUserID + "/finish_time", ServerValue.TIMESTAMP);
            }
        }

        FirebaseDatabase database = FirebaseDatabase.getInstance();
        DatabaseReference callRef = database.getReference("call").child(callID);
        callRef.updateChildren(callUpdates).addOnSuccessListener(new OnSuccessListener<Void>() {
            @Override
            public void onSuccess(Void unused) {
                Log.d(TAG, "endCallUser onSuccess() called with: unused = [" + unused + "]");
                if (callback != null) {
                    callback.onResult(0, callID);
                }
            }
        }).addOnFailureListener(new OnFailureListener() {
            @Override
            public void onFailure(@NonNull Exception e) {
                Log.d(TAG, "endCallUser onFailure() called with: e = [" + e + "]");
                if (callback != null) {
                    callback.onResult(-1000, null);
                }
            }
        });
        removeCallListener(callID);
    }

    private void removeDatabaseListener(String path) {
        if (databaseListenerMap.containsKey(path)) {
            FirebaseDatabase database = FirebaseDatabase.getInstance();
            ValueEventListener valueEventListener = databaseListenerMap.remove(path);
            if (valueEventListener != null) {
                database.getReference(path).removeEventListener(valueEventListener);
            }
        }
    }

    private void addDatabaseListener(String path, ValueEventListener listener) {
        if (!databaseListenerMap.containsKey(path)) {
            FirebaseDatabase database = FirebaseDatabase.getInstance();
            database.getReference(path).addValueEventListener(listener);
            databaseListenerMap.put(path, listener);
        }
    }

    private void setCurrentCallData(DatabaseCall call) {
        Log.d(TAG, "setCurrentCallData() called with: call = [" + call.call_id + "]");
        if (!isCurrentIdle()) {
            selfCalls.clear();
        }
        selfCalls.add(call);
    }

    private void removeCallData(String callID) {
        if (!isCurrentIdle()) {
            Iterator<DatabaseCall> iterator = selfCalls.iterator();
            while (iterator.hasNext()) {
                DatabaseCall next = iterator.next();
                if (Objects.equals(next.call_id, callID)) {
                    selfCalls.remove(next);
                }
            }
        }
    }

    private void clearCallData() {
        selfCalls.clear();
    }

    private boolean isCurrentIdle() {
        return selfCalls.isEmpty();
    }

    private boolean isCurrentCall(String callID) {
        if (isCurrentIdle()) {
            return false;
        } else {
            DatabaseCall databaseCall = selfCalls.get(0);
            return Objects.equals(databaseCall.call_id, callID);
        }
    }

    private DatabaseCall getCurrentCallData() {
        if (isCurrentIdle()) {
            return null;
        } else {
            return selfCalls.get(0);
        }
    }
}
