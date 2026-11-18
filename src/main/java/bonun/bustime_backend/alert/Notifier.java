package bonun.bustime_backend.alert;

// 알림 전송 창구. 테스트에서는 보낸 메시지를 모으는 가짜로 바꿔 끼운다.
public interface Notifier {

	void send(String message);
}
