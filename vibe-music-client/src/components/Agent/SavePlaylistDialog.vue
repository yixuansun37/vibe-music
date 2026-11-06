<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getSongDetail, saveDancePlaylist } from '@/api/system'
import type { SongDetail } from '@/api/interface'
import type { VibeSong } from '@/utils/agent'
import { UserStore } from '@/stores/modules/user'

const props = defineProps<{ songs: VibeSong[] }>()
const router = useRouter()
const user = UserStore()
const visible = ref(false)
const title = ref('')
const description = ref('')
const verifying = ref(false)
const saving = ref(false)
const errorMessage = ref('')
const verifiedSongs = ref<Pick<SongDetail, 'songId' | 'songName' | 'artistName'>[]>([])
const savedPlaylistId = ref<number | null>(null)
const busy = computed(() => verifying.value || saving.value)
const canSave = computed(() =>
  !busy.value && !!title.value.trim() && title.value.length <= 255 &&
  description.value.length <= 2000 && verifiedSongs.value.length > 0 &&
  verifiedSongs.value.length <= 50
)

const isLoggedIn = () => user.isLoggedIn && !!user.userInfo?.token

const openDialog = async () => {
  if (!isLoggedIn()) {
    ElMessage.warning('请先通过页面右上角登录后保存歌单')
    return
  }
  visible.value = true
  title.value = '舞蹈练习歌单'
  description.value = ''
  errorMessage.value = ''
  verifiedSongs.value = []
  const ids = [...new Set(props.songs.map((song) => song.songId))]
  if (ids.length === 0 || ids.length > 50 || ids.some((id) => !Number.isSafeInteger(id) || id <= 0)) {
    errorMessage.value = '请选择 1–50 首有效歌曲'
    return
  }

  verifying.value = true
  try {
    // 只把卡片 ID 当作候选；提交的是歌曲详情接口实际返回的 ID。
    // 任一候选查证失败就阻止整次保存，不默默丢弃歌曲。
    verifiedSongs.value = await Promise.all(ids.map(async (id) => {
      const response = await getSongDetail(id)
      const song = response.data as unknown as SongDetail | undefined
      if (response.code !== 0 || !song || !Number.isSafeInteger(song.songId) ||
          song.songId !== id || typeof song.songName !== 'string' || !song.songName.trim()) {
        throw new Error('部分推荐歌曲无法在曲库中确认，请重新生成推荐后再保存')
      }
      return { songId: song.songId, songName: song.songName, artistName: song.artistName }
    }))
  } catch {
    errorMessage.value = '无法确认全部歌曲，请检查网络或重新生成推荐后再保存'
  } finally {
    verifying.value = false
  }
}

const save = async () => {
  if (!canSave.value || savedPlaylistId.value) return
  if (!isLoggedIn()) {
    errorMessage.value = '登录已失效，请重新登录后保存'
    return
  }

  saving.value = true
  errorMessage.value = ''
  try {
    const response = await saveDancePlaylist({
      title: title.value.trim(),
      ...(description.value.trim() ? { description: description.value.trim() } : {}),
      songIds: verifiedSongs.value.map((song) => song.songId),
    })
    if (response.code !== 0) {
      errorMessage.value = response.message || '保存失败，请修改后重试'
      return
    }
    if (!Number.isSafeInteger(response.data) || (response.data as number) <= 0) {
      errorMessage.value = '未收到有效的歌单 ID，请到歌单列表确认保存结果后再重试'
      return
    }
    savedPlaylistId.value = response.data as number
    ElMessage.success('歌单保存成功')
  } catch (error: unknown) {
    const failure = error as { response?: { status?: number; data?: { message?: string } } }
    const status = failure.response?.status
    errorMessage.value = status === 401 ? '登录已失效，请重新登录后保存' :
      status === 403 ? '当前账号没有保存歌单的权限，请使用普通用户账号登录' :
      failure.response?.data?.message || '保存失败，请检查网络和服务状态后重试'
  } finally {
    saving.value = false
  }
}

const viewPlaylist = async () => {
  if (!savedPlaylistId.value) return
  await router.push(`/playlist/${savedPlaylistId.value}`)
  visible.value = false
}
</script>

<template>
  <el-button v-if="savedPlaylistId" size="small" type="success" @click="viewPlaylist">
    查看已保存歌单
  </el-button>
  <el-button v-else size="small" type="primary" @click="openDialog">
    保存歌单（Save Playlist）
  </el-button>

  <el-dialog
    v-model="visible"
    title="保存练习歌单"
    width="min(460px, calc(100vw - 32px))"
    append-to-body
    :close-on-click-modal="!busy"
    :close-on-press-escape="!busy"
    :show-close="!busy"
  >
    <el-alert v-if="savedPlaylistId" title="歌单已保存，可以前往查看并播放" type="success" :closable="false" show-icon />
    <template v-else>
      <p class="mb-4 text-sm text-muted-foreground">
        歌单公开可见；保存后可能按不同顺序显示歌曲。
      </p>
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item label="歌单标题" required>
          <el-input v-model="title" maxlength="255" show-word-limit :disabled="saving" placeholder="请输入歌单标题" />
        </el-form-item>
        <el-form-item label="描述（可选）">
          <el-input v-model="description" type="textarea" :rows="3" maxlength="2000" show-word-limit :disabled="saving" placeholder="例如：基础动作练习" />
        </el-form-item>
      </el-form>
      <p v-if="verifying" class="text-sm text-muted-foreground" role="status">正在核对曲库中的歌曲...</p>
      <div v-else-if="verifiedSongs.length" class="mb-3 text-sm">
        <p class="font-medium">将保存 {{ verifiedSongs.length }} 首曲库歌曲</p>
        <ul class="mt-2 max-h-32 overflow-y-auto space-y-1">
          <li v-for="song in verifiedSongs" :key="song.songId">{{ song.songName }} · {{ song.artistName }}</li>
        </ul>
      </div>
      <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" show-icon role="alert" />
    </template>
    <template #footer>
      <el-button :disabled="busy" @click="visible = false">{{ savedPlaylistId ? '关闭' : '取消' }}</el-button>
      <el-button v-if="savedPlaylistId" type="primary" @click="viewPlaylist">查看歌单</el-button>
      <el-button v-else type="primary" :loading="busy" :disabled="!canSave" @click="save">
        {{ verifying ? '核对中' : saving ? '保存中' : '保存' }}
      </el-button>
    </template>
  </el-dialog>
</template>
