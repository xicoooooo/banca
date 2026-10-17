import { useEffect } from 'react'
import { playAmbience, stopAmbience } from './roomSound'
import { useAtmosphere } from './atmosphere'
import { SOUND_CHANGED, isMuted } from './sound'

/**
 * Keeps the room's sound playing as the player has asked: started once they
 * have touched the page and sound is on, stopped while the page is out of
 * sight or sound is turned off, and changed when they choose another. It
 * draws nothing.
 */
export function Ambience() {
  const [{ ambience }] = useAtmosphere()

  useEffect(() => {
    const follow = () => {
      // Turning sound off silences the room too, and so does Banca going out of sight.
      if (document.hidden || isMuted()) stopAmbience()
      else playAmbience(ambience)
    }
    follow()
    window.addEventListener(SOUND_CHANGED, follow)
    document.addEventListener('visibilitychange', follow)
    return () => {
      window.removeEventListener(SOUND_CHANGED, follow)
      document.removeEventListener('visibilitychange', follow)
    }
  }, [ambience])

  // Leaving the app altogether is silence.
  useEffect(() => () => stopAmbience(), [])

  return null
}
