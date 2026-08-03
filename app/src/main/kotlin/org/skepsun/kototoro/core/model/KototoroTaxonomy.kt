package org.skepsun.kototoro.core.model

import org.skepsun.kototoro.core.util.ChineseConverter
import java.util.Locale

private val TRADITIONAL_CHINESE_REGIONS = setOf("HK", "MO", "TW")

private fun Locale.usesTraditionalChinese(): Boolean =
	language == Locale.CHINESE.language &&
		(script.equals("Hant", ignoreCase = true) || country.uppercase(Locale.ROOT) in TRADITIONAL_CHINESE_REGIONS)

enum class TaxonomyCategory(val id: String, val englishLabel: String, val chineseLabel: String) {
	GENRE("genre", "Genre", "题材"),
	THEME("theme", "Theme", "主题"),
	SETTING("setting", "Setting", "设定"),
	CHARACTER("character", "Character", "角色"),
	RELATIONSHIP("relationship", "Relationship", "人物关系"),
	NARRATIVE("narrative", "Narrative", "叙事"),
	ELEMENT("element", "Element", "元素"),
	TONE("tone", "Tone", "氛围"),
	FORMAT("format", "Format", "作品形式"),
	AUDIENCE("audience", "Audience", "受众"),
	WARNING("warning", "Content warning", "内容提示"),
	RAW("raw", "Source tags", "来源标签"),
	;

	val traditionalChineseLabel: String by lazy { ChineseConverter.s2t(chineseLabel) }

	fun displayName(locale: Locale = Locale.getDefault()): String = when {
		locale.usesTraditionalChinese() -> traditionalChineseLabel
		locale.language == Locale.CHINESE.language -> chineseLabel
		else -> englishLabel
	}

	companion object {
		fun fromTagId(id: String): TaxonomyCategory? =
			entries.firstOrNull { id.startsWith("${it.id}.") }
	}
}

data class TaxonomyTag(
	val id: String,
	val category: TaxonomyCategory,
	val englishLabel: String,
	val chineseLabel: String,
	val traditionalChineseLabel: String = ChineseConverter.s2t(chineseLabel),
	val aliases: Set<String> = emptySet(),
	val adult: Boolean = false,
	val sensitive: Boolean = category == TaxonomyCategory.WARNING,
	val deprecated: Boolean = false,
) {
	fun displayName(locale: Locale = Locale.getDefault()): String = when {
		locale.usesTraditionalChinese() -> traditionalChineseLabel
		locale.language == Locale.CHINESE.language -> chineseLabel
		else -> englishLabel
	}
}

/**
 * Kototoro Taxonomy v0.1.
 *
 * Source tags remain untouched. This catalog provides stable cross-source IDs and
 * exact alias mappings for filtering and presentation.
 */
object KototoroTaxonomy {

	val tags: List<TaxonomyTag> by lazy {
		TAXONOMY_DATA.lineSequence()
			.filter(String::isNotBlank)
			.map { row ->
				val (id, chineseLabel) = row.split('|', limit = 2)
				val category = requireNotNull(TaxonomyCategory.fromTagId(id)) { "Unknown taxonomy category: $id" }
				TaxonomyTag(
					id = id,
					category = category,
					englishLabel = id.substringAfter('.').toEnglishLabel(),
					chineseLabel = chineseLabel,
					aliases = CURATED_ALIASES.filterValues { id in it }.keys,
					adult = id == "genre.erotica" || id == "audience.adult",
				)
			}
			.toList()
	}

	private val tagsById: Map<String, TaxonomyTag> by lazy { tags.associateBy(TaxonomyTag::id) }

	private val idsByAlias: Map<String, Set<String>> by lazy {
		buildMap<String, MutableSet<String>> {
			fun add(alias: String, id: String) {
				getOrPut(normalize(alias), ::LinkedHashSet) += id
			}
			tags.forEach { tag ->
				add(tag.id, tag.id)
				add(tag.id.substringAfter('.'), tag.id)
				add(tag.englishLabel, tag.id)
				add(tag.chineseLabel, tag.id)
				add(tag.traditionalChineseLabel, tag.id)
				tag.aliases.forEach { add(it, tag.id) }
			}
			CURATED_ALIASES.forEach { (alias, ids) ->
				ids.forEach { id ->
					require(id in tagsById) { "Unknown taxonomy ID in alias mapping: $id" }
					add(alias, id)
				}
			}
		}.mapValues { it.value.toSet() }
	}

	fun find(id: String): TaxonomyTag? = tagsById[id]

	val knownSourceTags: List<String> by lazy {
		FUTON_SOURCE_TAGS.lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList()
	}

	fun resolve(rawTag: String): Set<TaxonomyTag> =
		idsByAlias[normalize(rawTag)].orEmpty().mapNotNullTo(LinkedHashSet(), tagsById::get)

	fun search(tag: TaxonomyTag, query: String): Boolean {
		val normalizedQuery = normalize(query)
		return normalizedQuery.isEmpty() ||
			normalize(tag.id).contains(normalizedQuery) ||
			normalize(tag.englishLabel).contains(normalizedQuery) ||
			normalize(tag.chineseLabel).contains(normalizedQuery) ||
			normalize(tag.traditionalChineseLabel).contains(normalizedQuery) ||
			tag.aliases.any { normalize(it).contains(normalizedQuery) }
	}

	internal fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)

	private fun String.toEnglishLabel(): String = split('-').joinToString(" ") { word ->
		word.replaceFirstChar { it.titlecase(Locale.ENGLISH) }
	}

	private val CURATED_ALIASES = mapOf(
		"sci-fi" to setOf("genre.science-fiction"),
		"science fiction" to setOf("genre.science-fiction"),
		"school life" to setOf("setting.academy"),
		"office workers" to setOf("setting.office"),
		"isekai" to setOf("setting.other-world"),
		"异世界题材" to setOf("setting.other-world"),
		"murim" to setOf("setting.martial-arts-world"),
		"武林" to setOf("setting.martial-arts-world"),
		"cultivation world" to setOf("setting.cultivation-world"),
		"修真" to setOf("element.cultivation", "setting.cultivation-world"),
		"virtual reality" to setOf("element.virtual-reality", "setting.virtual-world"),
		"bl" to setOf("relationship.boys-love"),
		"boylove" to setOf("relationship.boys-love"),
		"boys love" to setOf("relationship.boys-love"),
		"boys' love" to setOf("relationship.boys-love"),
		"yaoi" to setOf("relationship.boys-love"),
		"soft yaoi" to setOf("relationship.boys-love"),
		"shounen ai" to setOf("relationship.boys-love"),
		"gl" to setOf("relationship.girls-love"),
		"girl love" to setOf("relationship.girls-love"),
		"girls love" to setOf("relationship.girls-love"),
		"girls' love" to setOf("relationship.girls-love"),
		"yuri" to setOf("relationship.girls-love"),
		"soft yuri" to setOf("relationship.girls-love"),
		"shoujo ai" to setOf("relationship.girls-love"),
		"vengeance" to setOf("theme.revenge"),
		"复仇题材" to setOf("theme.revenge"),
		"ecc" to setOf("genre.ecchi"),
		"ecchi" to setOf("genre.ecchi"),
		"anime" to setOf("format.anime-series"),
		"anthropomorphic" to setOf("element.anthropomorphic-animals"),
		"biography" to setOf("genre.biographical"),
		"dungeons" to setOf("setting.dungeon"),
		"gender bender" to setOf("narrative.gender-swap"),
		"heartwarming" to setOf("tone.wholesome"),
		"hentai" to setOf("audience.adult", "warning.explicit-sexual-content"),
		"idol" to setOf("character.performer", "theme.show-business"),
		"mafia" to setOf("genre.crime"),
		"magical girls" to setOf("character.magical-girl"),
		"mature" to setOf("audience.adult"),
		"medical" to setOf("theme.medicine"),
		"monster girl" to setOf("character.monster"),
		"nsfw" to setOf("audience.adult"),
		"overpower" to setOf("character.overpowered-protagonist"),
		"political" to setOf("theme.politics"),
		"religious" to setOf("theme.religion"),
		"romcom" to setOf("genre.romance", "genre.comedy"),
		"rpg" to setOf("setting.game-world", "narrative.game-mechanics"),
		"school" to setOf("setting.academy"),
		"smut" to setOf("genre.erotica"),
		"spy" to setOf("theme.espionage"),
		"suspense" to setOf("genre.thriller", "tone.suspenseful"),
		"tragedy" to setOf("tone.tragic"),
		"video games" to setOf("setting.game-world", "narrative.game-mechanics"),
		"wuxia" to setOf("genre.martial-arts", "setting.martial-arts-world"),
		"yakuza" to setOf("genre.crime"),
		"acao" to setOf("genre.action"),
		"aksiyon" to setOf("genre.action"),
		"aventura" to setOf("genre.adventure"),
		"comedia" to setOf("genre.comedy"),
		"komedi" to setOf("genre.comedy"),
		"fantasia" to setOf("genre.fantasy"),
		"ficcao" to setOf("genre.science-fiction"),
		"misterio" to setOf("genre.mystery"),
		"psicologico" to setOf("genre.psychological"),
		"historico" to setOf("genre.historical"),
		"magia" to setOf("element.magic"),
		"medico" to setOf("theme.medicine"),
		"militar" to setOf("theme.military"),
		"mitologia" to setOf("element.mythology"),
		"musica" to setOf("genre.music"),
		"sobrenatural" to setOf("genre.supernatural"),
		"deportes" to setOf("genre.sports"),
		"esporte" to setOf("genre.sports"),
		"recuentos de la vida" to setOf("genre.slice-of-life"),
		"artes marciais" to setOf("genre.martial-arts"),
		"artes marciales" to setOf("genre.martial-arts"),
		"culinaria" to setOf("theme.cooking"),
		"vinganca" to setOf("theme.revenge"),
		"policia" to setOf("genre.crime", "theme.detective-work"),
		"tragedia" to setOf("tone.tragic"),
		"sobrevivencia" to setOf("theme.survival"),
		"supervivencia" to setOf("theme.survival"),
		"escolar" to setOf("setting.academy"),
		"escritorio" to setOf("setting.office"),
		"reencarnacao" to setOf("narrative.reincarnation"),
		"regressao" to setOf("narrative.regression"),
		"retorno" to setOf("narrative.second-chance"),
		"viagem no tempo" to setOf("narrative.time-travel"),
		"sistema" to setOf("narrative.system-mechanics"),
		"poderes" to setOf("element.superpowers"),
		"demonios" to setOf("element.demons"),
		"fantasma" to setOf("element.ghosts"),
		"monstros" to setOf("element.monsters"),
		"vampiros" to setOf("element.vampires"),
		"zumbi" to setOf("element.zombies"),
		"zombie" to setOf("element.zombies"),
		"vampire" to setOf("element.vampires"),
		"ghost" to setOf("element.ghosts"),
		"robot" to setOf("element.robots"),
		"alien" to setOf("element.aliens"),
		"monster" to setOf("element.monsters"),
		"webtoons" to setOf("format.webtoon"),
		"web comic" to setOf("format.webtoon"),
		"webcomic" to setOf("format.webtoon"),
		"graphic novels" to setOf("format.graphic-novel"),
		"one shot" to setOf("format.one-shot"),
		"oneshot" to setOf("format.one-shot"),
		"live action" to setOf("format.live-action-series", "format.live-action-film"),
		"yonkoma" to setOf("format.comic-strip"),
		"monster girls" to setOf("character.monster"),
		"leading ladies" to setOf("character.female-protagonist"),
		"sport" to setOf("genre.sports"),
		"postapocalypse" to setOf("setting.post-apocalyptic"),
		"dark fantasy" to setOf("genre.fantasy", "tone.dark"),
		"second chance" to setOf("narrative.second-chance"),
		"time manipulation" to setOf("narrative.time-travel"),
		"reincarnated in another world" to setOf(
			"narrative.reincarnation",
			"setting.other-world",
		),
	)

	private const val TAXONOMY_DATA = """
genre.action|动作
genre.adventure|冒险
genre.comedy|喜剧
genre.drama|剧情
genre.romance|爱情
genre.fantasy|奇幻
genre.science-fiction|科幻
genre.mystery|悬疑
genre.thriller|惊悚
genre.horror|恐怖
genre.crime|犯罪
genre.supernatural|超自然
genre.psychological|心理
genre.historical|历史
genre.war|战争
genre.sports|体育
genre.slice-of-life|日常
genre.family|家庭
genre.music|音乐
genre.performing-arts|表演艺术
genre.martial-arts|武术
genre.western|西部
genre.documentary|纪录
genre.biographical|传记
genre.erotica|情色
genre.ecchi|轻度情色
theme.coming-of-age|成长
theme.friendship|友情
theme.family|家庭
theme.parenthood|亲子
theme.love|爱
theme.revenge|复仇
theme.survival|生存
theme.redemption|救赎
theme.self-discovery|自我探索
theme.identity|身份认同
theme.ambition|野心
theme.power-struggle|权力斗争
theme.politics|政治
theme.social-conflict|社会冲突
theme.class-conflict|阶级冲突
theme.discrimination|歧视
theme.bullying|欺凌
theme.war-and-peace|战争与和平
theme.life-and-death|生死
theme.grief|悲伤与失去
theme.trauma|创伤
theme.healing|治愈
theme.fate|命运
theme.freedom|自由
theme.justice|正义
theme.corruption|腐败
theme.conspiracy|阴谋
theme.morality|道德抉择
theme.human-nature|人性
theme.religion|宗教
theme.environment|环境
theme.technology|科技
theme.artificial-intelligence|人工智能
theme.business|商业
theme.cooking|烹饪
theme.gambling|赌博
theme.medicine|医疗
theme.detective-work|侦探调查
theme.military|军事
theme.espionage|谍战
theme.show-business|演艺圈
theme.game-competition|游戏竞技
theme.parental-love|亲情守护
theme.unrequited-love|单恋
theme.forbidden-love|禁忌之恋
setting.contemporary|现代
setting.historical|历史时代
setting.ancient|古代
setting.medieval|中世纪
setting.future|未来
setting.near-future|近未来
setting.post-apocalyptic|末世
setting.dystopia|反乌托邦
setting.utopia|乌托邦
setting.cyberpunk|赛博朋克
setting.steampunk|蒸汽朋克
setting.space|太空
setting.space-colony|太空殖民地
setting.alternate-history|架空历史
setting.parallel-world|平行世界
setting.other-world|异世界
setting.virtual-world|虚拟世界
setting.game-world|游戏世界
setting.academy|学院
setting.high-school|高中
setting.university|大学
setting.workplace|职场
setting.office|办公室
setting.hospital|医院
setting.prison|监狱
setting.military|军队
setting.royal-court|宫廷
setting.nobility|贵族社会
setting.countryside|乡村
setting.small-town|小镇
setting.city|都市
setting.island|岛屿
setting.wilderness|荒野
setting.underwater|水下世界
setting.dungeon|地下城
setting.magic-world|魔法世界
setting.cultivation-world|修仙世界
setting.martial-arts-world|武侠江湖
setting.monster-society|怪物社会
setting.superhero-society|超级英雄社会
character.male-protagonist|男性主角
character.female-protagonist|女性主角
character.non-human-protagonist|非人类主角
character.multiple-protagonists|多主角
character.ensemble-cast|群像
character.child-protagonist|儿童主角
character.adult-protagonist|成年主角
character.antihero-protagonist|反英雄主角
character.villain-protagonist|反派主角
character.unreliable-protagonist|不可靠主角
character.royalty|王族
character.nobility|贵族
character.commoner|平民
character.student|学生
character.teacher|教师
character.office-worker|上班族
character.detective|侦探
character.criminal|犯罪者
character.assassin|刺客
character.soldier|军人
character.doctor|医生
character.artist|艺术家
character.performer|演艺人员
character.athlete|运动员
character.mage|魔法师
character.cultivator|修炼者
character.reincarnated-person|转生者
character.transmigrated-person|穿越者
character.overpowered-protagonist|强大主角
character.weak-to-strong|弱者成长
character.hidden-identity|隐藏身份
character.secret-power|隐藏能力
character.amnesia|失忆角色
character.artificial-human|人造人
character.robot|机器人
character.monster|怪物角色
character.vampire|吸血鬼
character.werewolf|狼人
character.demon|恶魔
character.angel|天使
character.ghost|幽灵
character.beastfolk|兽人
character.magical-girl|魔法少女
character.superhero|超级英雄
relationship.friendship|友情关系
relationship.found-family|非血缘家庭
relationship.siblings|兄弟姐妹
relationship.parent-child|亲子关系
relationship.master-disciple|师徒关系
relationship.rivals|对手关系
relationship.enemies-to-lovers|相爱相杀
relationship.friends-to-lovers|朋友变恋人
relationship.childhood-friends|青梅竹马
relationship.contract-relationship|契约关系
relationship.arranged-marriage|包办婚姻
relationship.fake-relationship|假扮情侣
relationship.age-gap|年龄差
relationship.interclass-romance|阶层差恋爱
relationship.interracial-romance|跨种族恋爱
relationship.long-distance|异地关系
relationship.love-triangle|三角恋
relationship.polyamory|多边恋爱
relationship.harem|后宫
relationship.reverse-harem|逆后宫
relationship.boys-love|男性间恋爱
relationship.girls-love|女性间恋爱
relationship.heterosexual-romance|异性恋爱
relationship.co-parenting|共同养育
relationship.toxic-relationship|有害关系
relationship.obsessive-love|偏执之爱
relationship.forbidden-relationship|禁忌关系
narrative.reincarnation|转生
narrative.transmigration|穿越
narrative.time-travel|时间旅行
narrative.time-loop|时间循环
narrative.regression|回归
narrative.second-chance|重获机会
narrative.body-swap|身体互换
narrative.gender-swap|性别转换
narrative.possession|附身
narrative.identity-swap|身份互换
narrative.parallel-timelines|平行时间线
narrative.multiverse|多元宇宙
narrative.alternate-reality|替代现实
narrative.simulation|模拟世界
narrative.system-mechanics|系统流
narrative.leveling|等级成长
narrative.game-mechanics|游戏机制
narrative.survival-game|生存游戏
narrative.death-game|死亡游戏
narrative.battle-royale|大逃杀
narrative.tournament|竞赛大会
narrative.quest|任务冒险
narrative.road-trip|公路旅程
narrative.training-arc|修行成长
narrative.revenge-plot|复仇主线
narrative.mystery-solving|解谜主线
narrative.political-intrigue|政治权谋
narrative.kingdom-building|建国经营
narrative.management|经营建设
narrative.episodic|单元剧
narrative.anthology|选集结构
narrative.nonlinear|非线性叙事
narrative.multiple-perspectives|多视角
narrative.unreliable-narrator|不可靠叙述者
narrative.story-within-story|戏中戏
narrative.meta-fiction|元叙事
narrative.slow-burn|慢热
narrative.fast-paced|快节奏
narrative.open-ending|开放式结局
narrative.tragic-ending|悲剧结局
narrative.happy-ending|圆满结局
element.magic|魔法
element.superpowers|超能力
element.psychic-powers|精神能力
element.cultivation|修炼
element.martial-arts|武术
element.swordsmanship|剑术
element.alchemy|炼金术
element.summoning|召唤
element.necromancy|死灵术
element.spirits|灵体
element.monsters|怪物
element.demons|恶魔
element.dragons|龙
element.gods|神明
element.mythology|神话
element.folklore|民间传说
element.mecha|机甲
element.robots|机器人
element.androids|仿生人
element.artificial-intelligence|人工智能
element.virtual-reality|虚拟现实
element.aliens|外星人
element.space-travel|太空旅行
element.time-machine|时间机器
element.cloning|克隆
element.cybernetics|机械改造
element.zombies|丧尸
element.vampires|吸血鬼
element.werewolves|狼人
element.ghosts|幽灵
element.talking-animals|会说话的动物
element.anthropomorphic-animals|拟人动物
tone.lighthearted|轻松
tone.wholesome|温馨
tone.comedic|欢乐
tone.romantic|浪漫
tone.emotional|感人
tone.melancholic|忧郁
tone.dark|黑暗
tone.gritty|冷峻写实
tone.suspenseful|紧张
tone.disturbing|令人不安
tone.absurd|荒诞
tone.satirical|讽刺
tone.philosophical|哲思
tone.relaxing|放松
tone.inspirational|励志
tone.tragic|悲剧氛围
format.manga|日式漫画
format.manhwa|韩式漫画
format.manhua|中文漫画
format.webtoon|条漫
format.comic-strip|四格或短篇条漫
format.graphic-novel|图像小说
format.one-shot|单篇
format.anthology|选集
format.light-novel|轻小说
format.web-novel|网络小说
format.visual-novel|视觉小说
format.short-story|短篇小说
format.novella|中篇小说
format.serial-novel|连载小说
format.anime-series|动画剧集
format.animated-film|动画电影
format.live-action-series|真人剧集
format.live-action-film|真人电影
format.short-film|短片
format.ova|OVA
format.ona|ONA
format.special|特别篇
format.music-video|音乐视频
format.documentary|纪录片
format.variety-show|综艺
format.motion-comic|动态漫画
audience.children|儿童
audience.teen|青少年
audience.young-adult|青年
audience.adult|成人
audience.all-ages|全年龄
audience.family|家庭向
warning.violence|暴力
warning.graphic-violence|强烈暴力
warning.gore|血腥
warning.death|死亡
warning.child-death|儿童死亡
warning.suicide|自杀内容
warning.self-harm|自残内容
warning.abuse|虐待
warning.child-abuse|儿童虐待
warning.domestic-violence|家庭暴力
warning.sexual-violence|性暴力
warning.sexual-content|性内容
warning.explicit-sexual-content|露骨性内容
warning.nudity|裸露
warning.incest|乱伦内容
warning.age-gap-minor|涉及未成年人的年龄差关系
warning.substance-abuse|药物滥用
warning.alcohol-abuse|酒精滥用
warning.gambling|赌博内容
warning.bullying|欺凌
warning.torture|酷刑
warning.kidnapping|绑架
warning.human-trafficking|人口贩卖
warning.slavery|奴役
warning.discrimination|歧视
warning.hate-speech|仇恨言论
warning.eating-disorder|饮食障碍
warning.terminal-illness|绝症
warning.animal-death|动物死亡
warning.flashing-lights|闪烁画面
warning.jump-scares|突发惊吓
"""

	private const val FUTON_SOURCE_TAGS = """
aboo
acao
act
action
adaption
adu
adult
adv
adventure
aksiyon
aliens
ani
animals
anime
anthology
anthropomorphic
art
artes marciais
artes marciales
atlus
attributes
author
aventura
award winning
bara
bbl
biography
blm
boylove
boys love
children
codomo
coi
colored
com
comedia
comedy
comic
coo
cooking
crime
crossdressing
culinaria
cultivo
cyberpunk
dark fantasy
databook
delinquentes
demonios
deportes
detective
dou
doujinshi
dra
drama
dun
dungeons
ecc
ecchi
eroge
erotica
escolar
escritorio
esporte
event bt
family
fantasia
fantasma
fantastic
fantasy
ficcao
fighting
filosofico
fts
gam
game
gdb
gender bender
ghosts
girl love
girls love
gore
graphic novel
graphic novels
gyaru
har
harem
heartwarming
hentai
his
historical
historico
hor
horror
hunt
id
idol
informative
input
ise
isekai
jos
josei
komedi
label
leading ladies
lgbt
lgbtq
literature
live action
loli
ltt
maa
maduro
mafia
mag
magia
magic
magical girls
manga
manhua
manhwa
martial arts
mat
mature
mau
maw
mecha
medical
medico
militar
military
misterio
mitologia
monster girl
monster girls
monsters
monstros
mrr
murim
music
musica
musical
mys
mystery
mythology
nam x nam
name
natural
nct
ninja
nsfw
ntnc
ntr
ntt
omegavers
onclick
one shot
oneshot
osh
overpower
personal
philosophical
poderes
policia
political
postapocalypse
psicologico
psychological
pulp
recuentos de la vida
red
reencarnacao
regressao
religious
retorno
robots
rom
romance
romcom
rpg
samurai
scf
school
school life
scl
sega
seinen
sf
shi
sho
shotacon
shoujo
shoujo ai
shounen
shounen ai
sistema
slice of life
slug
smut
sobrenatural
sobrevivencia
soft yaoi
soft yuri
sol
span
spo
sport
sports
spy
sun
superhero
supernatural
supervivencia
survival
suspense
teen
terror
thriller
tiptoon
title
torre
tra
tragedia
tragedy
value
vampires
vampiros
viagem no tempo
video clip
video games
vila
vilao
vinganca
virtual reality
vncomic
war
web
web comic
webtoon
webtoons
western
wholesome
wuxia
yakuza
yaoi
yonkoma
yuri
zombie
zombies
zumbi
"""
}
