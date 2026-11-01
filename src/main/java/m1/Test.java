package m1;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/**
 * @author syp ૮ ˘͈ᵕ ˘͈ ა
 * @description TODO
 * @date 2026-11-01 17:46
 */
public class Test {
    public static void main(String[] args) throws Exception {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", "明朝有多少位皇帝"),
                Map.of("role", "assistant", "content", "共有16位皇帝,这 16 位是：太祖、惠帝、成祖、仁宗、宣宗、英宗、代宗、宪宗、孝宗、武宗、世宗、穆宗、神宗、光宗、熹宗、思宗。"),
                Map.of("role", "user", "content", "那清朝呢")
        );
        HttpResponse<String> resp = Chat.post(Chat.buildBody(messages, false));
        JSONObject root = JSON.parseObject(resp.body());
        System.out.println(root.toString());
    }
}
