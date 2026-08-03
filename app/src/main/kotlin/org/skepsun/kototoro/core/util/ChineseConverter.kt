package org.skepsun.kototoro.core.util

import com.github.catvod.utils.Trans

object ChineseConverter {

    private val t2sMap: Map<Char, Char> = mapOf(
        '著' to '着', '乾' to '干', '瞭' to '了', '藉' to '借',
        '麼' to '么', '豐' to '丰', '門' to '门', '們' to '们',
        '學' to '学', '開' to '开', '關' to '关', '車' to '车',
        '東' to '东', '風' to '风', '現' to '现', '話' to '话',
        '萬' to '万', '邊' to '边', '會' to '会', '時' to '时',
        '後' to '后', '國' to '国', '來' to '来', '對' to '对',
        '動' to '动', '過' to '过', '種' to '种', '爲' to '为',
        '長' to '长', '書' to '书', '兒' to '儿', '見' to '见',
        '說' to '说', '頭' to '头', '經' to '经', '裏' to '里',
        '實' to '实', '體' to '体', '點' to '点', '機' to '机',
        '電' to '电', '發' to '发', '當' to '当', '還' to '还',
        '沒' to '没', '樣' to '样', '氣' to '气', '幾' to '几',
        '道' to '道', '進' to '进', '業' to '业', '從' to '从',
        '問' to '问', '間' to '间', '麵' to '面', '愛' to '爱',
        '聽' to '听', '無' to '无', '爾' to '尔', '給' to '给',
        '總' to '总', '聲' to '声', '處' to '处', '結' to '结',
        '識' to '识', '記' to '记', '數' to '数', '義' to '义',
        '師' to '师', '員' to '员', '孫' to '孙', '軍' to '军',
        '團' to '团', '連' to '连', '張' to '张', '歡' to '欢',
        '網' to '网', '妳' to '你', '紅' to '红', '綠' to '绿',
        '嗎' to '吗', '將' to '将', '覺' to '觉', '報' to '报',
        '場' to '场', '馬' to '马', '鳥' to '鸟', '魚' to '鱼',
        '龍' to '龙', '讓' to '让', '層' to '层', '帶' to '带',
        '寫' to '写', '達' to '达', '視' to '视', '變' to '变',
        '燈' to '灯', '戰' to '战', '禮' to '礼', '嗎' to '吗',
        '錢' to '钱', '鐵' to '铁', '飯' to '饭', '飛' to '飞',
        '級' to '级', '組' to '组', '嗎' to '吗', '舊' to '旧',
        '買' to '买', '賣' to '卖', '讀' to '读', '調' to '调',
        '應' to '应', '術' to '术', '裝' to '装', '術' to '术',
        '質' to '质', '區' to '区', '別' to '别', '處' to '处',
        '媽' to '妈', '讀' to '读', '確' to '确', '係' to '系',
        '樂' to '乐', '藥' to '药', '約' to '约', '遠' to '远',
        '歷' to '历', '號' to '号', '單' to '单', '殺' to '杀',
        '話' to '话', '該' to '该', '談' to '谈', '論' to '论',
        '請' to '请', '讓' to '让', '設' to '设', '計' to '计',
        '許' to '许', '語' to '语', '認' to '认', '誤' to '误',
        '說' to '说', '誰' to '谁', '課' to '课', '調' to '调',
        '變' to '变', '頭' to '头', '實' to '实', '書' to '书',
        '門' to '门', '問' to '问', '間' to '间', '開' to '开',
        '關' to '关', '閉' to '闭', '見' to '见', '聽' to '听',
        '電' to '电', '體' to '体', '點' to '点', '麵' to '面',
    )

    private val s2tMap: Map<Char, Char> by lazy {
        t2sMap.entries.associate { (t, s) -> s to t }
    }

    fun t2s(text: String): String {
        return Trans.t2s(false, text)
    }

    fun s2t(text: String): String {
        return Trans.s2t(false, text)
    }
}
