import { http } from '@/utils/http'
import type { VibeSong } from '@/utils/agent'

export type DanceStyle = 'hiphop' | 'house' | 'jazz'

export interface DancePracticeRequest {
  danceStyle: DanceStyle
  targetMinutes: number
}

export interface DancePracticeSong extends VibeSong {
  durationMs: number
}

export interface DancePracticeSection {
  key: 'warmup' | 'main' | 'freestyle'
  title: string
  targetDurationMs: number
  actualDurationMs: number
  songs: DancePracticeSong[]
}

export interface DancePracticePlan {
  danceStyle: string
  musicStyles: string[]
  targetDurationMs: number
  actualDurationMs: number
  shortfallMs: number
  generationMode: 'DETERMINISTIC' | 'AI_ASSISTED'
  candidateCount: number
  warnings: string[]
  sections: DancePracticeSection[]
}

/** 沿用现有 HTTP 客户端自动附带登录 token。 */
export const generateDancePractice = (data: DancePracticeRequest) => {
  return http<{ code: number; message: string; data?: DancePracticePlan }>(
    'post',
    '/playlist/dance/generate',
    {
      data,
      validateStatus: (status) => (status >= 200 && status < 300) || status === 400,
    }
  )
}
