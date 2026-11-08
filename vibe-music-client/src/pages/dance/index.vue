<script setup lang="ts">
import { computed, ref } from 'vue'
import SavePlaylistDialog from '@/components/Agent/SavePlaylistDialog.vue'
import { generateDancePractice } from '@/api/dance'
import type { DancePracticePlan, DanceStyle } from '@/api/dance'
import { UserStore } from '@/stores/modules/user'

const user = UserStore()
const styles: { value: DanceStyle; label: string; music: string }[] = [
  { value: 'hiphop', label: 'Hip-hop', music: '嘻哈说唱' },
  { value: 'house', label: 'House', music: '电子' },
  { value: 'jazz', label: 'Jazz dance', music: '节奏布鲁斯' },
]
const danceStyle = ref<DanceStyle>('hiphop')
const targetMinutes = ref<number | undefined>(20)
const loading = ref(false)
const errorMessage = ref('')
const plan = ref<DancePracticePlan | null>(null)
const planRevision = ref(0)
const isLoggedIn = computed(() => user.isLoggedIn && !!user.userInfo?.token)
const planStyle = computed(() => styles.find((style) => style.value === plan.value?.danceStyle)?.label || plan.value?.danceStyle)
// 仅采用生成接口返回的真实歌曲 ID，按预览顺序去重后交给现有保存组件再次查证。
const songsToSave = computed(() => {
  const seen = new Set<number>()
  return (plan.value?.sections.flatMap((section) => section.songs) || []).filter((song) => {
    if (!Number.isSafeInteger(song.songId) || song.songId <= 0 || seen.has(song.songId)) return false
    seen.add(song.songId)
    return true
  })
})

const formatDuration = (milliseconds: number) => {
  const duration = Math.max(0, Math.trunc(milliseconds))
  const seconds = Math.floor(duration / 1000)
  const fraction = duration % 1000
  const suffix = fraction ? `.${String(fraction).padStart(3, '0').replace(/0+$/, '')}` : ''
  return `${Math.floor(seconds / 60)}分${String(seconds % 60).padStart(2, '0')}${suffix}秒`
}

const generate = async () => {
  if (loading.value) return
  errorMessage.value = ''
  if (!isLoggedIn.value) {
    errorMessage.value = '请先通过页面右上角登录后生成练习歌单'
    return
  }
  const minutes = targetMinutes.value
  if (typeof minutes !== 'number' || !Number.isInteger(minutes) || minutes < 5 || minutes > 90) {
    errorMessage.value = '练习时长须为 5–90 分钟的整数'
    return
  }

  loading.value = true
  plan.value = null
  try {
    const response = await generateDancePractice({ danceStyle: danceStyle.value, targetMinutes: minutes })
    if (response.code !== 0 || !response.data) {
      errorMessage.value = response.message || '生成失败，请修改条件后重试'
      return
    }
    plan.value = response.data
    planRevision.value += 1
  } catch (error: unknown) {
    const failure = error as { response?: { status?: number; data?: { message?: string } } }
    const status = failure.response?.status
    errorMessage.value = status === 401 ? '登录已失效，请重新登录后生成' :
      status === 403 ? '当前账号无法生成练习歌单，请使用普通用户账号登录' :
      failure.response?.data?.message || '生成失败，请检查网络和后端服务后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="flex-1 overflow-y-auto p-4 md:p-8">
    <div class="mx-auto max-w-4xl space-y-6">
      <header>
        <p class="text-sm text-primary">DanceFlow</p>
        <h1 class="mt-1 text-2xl font-semibold">舞蹈练习歌单</h1>
        <p class="mt-2 text-sm text-muted-foreground">
          从现有曲库选择真实歌曲，预览热身、主练、自由练习三个阶段，再保存为歌单。
        </p>
      </header>

      <section class="rounded-xl border border-border bg-card p-5">
        <el-alert v-if="!isLoggedIn" title="请先通过页面右上角登录，生成和保存均需要登录。" type="info" :closable="false" show-icon class="mb-4" />
        <el-form label-position="top" @submit.prevent="generate">
          <div class="grid gap-x-5 sm:grid-cols-2">
            <el-form-item label="舞种">
              <el-select v-model="danceStyle" :disabled="loading" class="w-full" aria-label="舞种">
                <el-option v-for="style in styles" :key="style.value" :label="`${style.label}（${style.music}）`" :value="style.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="目标练习时长（分钟）">
              <el-input-number v-model="targetMinutes" :min="5" :max="90" :step="1" :precision="0" :disabled="loading" controls-position="right" aria-label="目标练习时长（分钟）" />
            </el-form-item>
          </div>
          <p class="mb-4 text-sm text-muted-foreground">
            舞种只用于匹配括号中的已有音乐分类，是练习选曲参考；曲库没有舞蹈适配或 BPM 标签。
          </p>
          <el-button type="primary" :loading="loading" :disabled="!isLoggedIn" native-type="submit">生成练习计划</el-button>
          <span v-if="loading" class="ml-3 text-sm text-muted-foreground" role="status">正在从曲库选曲...</span>
        </el-form>
      </section>

      <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" show-icon role="alert" />

      <template v-if="plan">
        <section class="rounded-xl border border-border bg-card p-5" aria-label="练习计划预览">
          <div class="flex flex-wrap items-start justify-between gap-4">
            <div>
              <h2 class="text-xl font-semibold">{{ planStyle }} · 练习预览</h2>
              <p class="mt-1 text-sm text-muted-foreground">曲库分类：{{ plan.musicStyles.join('、') }} · {{ plan.candidateCount }} 首候选歌曲</p>
            </div>
            <el-tag :type="plan.generationMode === 'AI_ASSISTED' ? 'success' : 'info'">
              {{ plan.generationMode === 'AI_ASSISTED' ? 'AI 辅助排序 + 规则分段' : '规则生成（非 AI）' }}
            </el-tag>
          </div>
          <dl class="my-5 grid grid-cols-1 gap-4 sm:grid-cols-3">
            <div><dt class="text-sm text-muted-foreground">目标时长</dt><dd class="mt-1 font-medium">{{ formatDuration(plan.targetDurationMs) }}</dd></div>
            <div><dt class="text-sm text-muted-foreground">实际时长 · {{ songsToSave.length }} 首</dt><dd class="mt-1 font-medium">{{ formatDuration(plan.actualDurationMs) }}</dd></div>
            <div><dt class="text-sm text-muted-foreground">未满足时长</dt><dd class="mt-1 font-medium">{{ formatDuration(plan.shortfallMs) }}</dd></div>
          </dl>
          <p class="text-sm text-muted-foreground">
            时长由曲库数据确定计算，歌曲不重复。AI 可用时仅辅助排列已有歌曲；无 AI 时仍可用规则生成。
          </p>
          <div v-if="plan.warnings.length" class="mt-4 space-y-2">
            <el-alert v-for="(warning, index) in plan.warnings" :key="index" :title="warning" type="warning" :closable="false" show-icon />
          </div>
        </section>

        <section v-for="section in plan.sections" :key="section.key" class="rounded-xl border border-border bg-card p-5">
          <div class="mb-4 flex flex-wrap items-center justify-between gap-2">
            <h3 class="text-lg font-semibold">{{ section.title }}</h3>
            <p class="text-sm text-muted-foreground">{{ section.songs.length }} 首 · 实际 {{ formatDuration(section.actualDurationMs) }} / 目标 {{ formatDuration(section.targetDurationMs) }}</p>
          </div>
          <ol v-if="section.songs.length" class="divide-y divide-border">
            <li v-for="(song, index) in section.songs" :key="song.songId" class="flex items-center gap-3 py-3">
              <span class="w-5 shrink-0 text-sm text-muted-foreground">{{ index + 1 }}</span>
              <div class="min-w-0 flex-1">
                <p class="truncate font-medium">{{ song.songName }}</p>
                <p class="truncate text-sm text-muted-foreground">{{ song.artistName }}<span v-if="song.album"> · {{ song.album }}</span></p>
              </div>
              <span class="shrink-0 text-sm text-muted-foreground">{{ formatDuration(song.durationMs) }}</span>
            </li>
          </ol>
          <p v-else class="text-sm text-muted-foreground">暂无符合当前阶段时长的歌曲，请查看提示或调整条件。</p>
        </section>

        <section class="rounded-xl border border-border bg-card p-5">
          <p class="mb-3 text-sm text-muted-foreground">
            将保存为公开歌单。当前数据库不保存练习分段，也不能保证歌单歌曲顺序；以上分段与顺序仅供本次练习预览。
          </p>
          <SavePlaylistDialog v-if="songsToSave.length" :key="planRevision" :songs="songsToSave" />
          <p v-else class="text-sm text-muted-foreground">没有可保存的歌曲，请调整舞种或时长后重新生成。</p>
        </section>
      </template>
    </div>
  </div>
</template>
