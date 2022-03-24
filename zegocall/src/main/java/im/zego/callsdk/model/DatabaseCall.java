package im.zego.callsdk.model;

import java.util.List;
import java.util.Map;

public class DatabaseCall {

    public String call_id;
    public int call_type;
    public int call_status;
    public Map<String,DatabaseCallUser> users;

    public static class DatabaseCallUser {

        public String caller_id;
        public String user_id;
        public long start_time;
        public long finish_time;
        public long heartbeat_time;
        public long connected_time;
        public int status;
    }

//    public enum CallStatus {
//        CALLING(1),
//        CONNECTED(2),
//        FINISHED(3);
//
//        private final int value;
//
//        public int getValue() {
//            return value;
//        }
//
//        CallStatus(int value) {
//            this.value = value;
//        }
//    }

    public enum Status {
        WAIT(1),
        CONNECTED(2),
        FINISHED(3),
        REJECTED(4),
        CANCELED(5),
        TIMEOUT_WAIT(6),
        TIMEOUT_CONNECTED(7);

        private final int value;

        public int getValue() {
            return value;
        }

        Status(int value) {
            this.value = value;
        }
    }
}
