import { AMBIENCES, THEMES, useAtmosphere } from '../casino/atmosphere'
import { isMuted, sound } from '../casino/sound'

/**
 * The light in the room and the sound under the game, chosen here and kept on
 * this device. A sound chosen plays at once, so it can be heard before it is kept.
 */
export function AtmosphereSetting() {
  const [atmosphere, setAtmosphere] = useAtmosphere()

  return (
    <>
      <h3 className="pb-2 text-sm font-semibold text-ivory">The light</h3>
      <div className="atmosphere-choices" role="group" aria-label="The light in the room">
        {THEMES.map((theme) => (
          <button
            key={theme.name}
            type="button"
            className="atmosphere-choice"
            data-selected={atmosphere.theme === theme.name}
            aria-pressed={atmosphere.theme === theme.name}
            onClick={() => {
              sound.click()
              setAtmosphere({ ...atmosphere, theme: theme.name })
            }}
          >
            <strong>{theme.title}</strong>
            <span>{theme.detail}</span>
          </button>
        ))}
      </div>

      <h3 className="pt-5 pb-2 text-sm font-semibold text-ivory">The sound of the room</h3>
      <div className="atmosphere-choices" role="group" aria-label="The sound of the room">
        {AMBIENCES.map((ambience) => (
          <button
            key={ambience.name}
            type="button"
            className="atmosphere-choice"
            data-selected={atmosphere.ambience === ambience.name}
            aria-pressed={atmosphere.ambience === ambience.name}
            onClick={() => {
              sound.click()
              setAtmosphere({ ...atmosphere, ambience: ambience.name })
            }}
          >
            <strong>{ambience.title}</strong>
            <span>{ambience.detail}</span>
          </button>
        ))}
      </div>
      <p className="pt-3 text-sm leading-relaxed text-muted">
        {isMuted() && atmosphere.ambience !== 'off'
          ? 'Sound is off at the moment. Turn it on with the speaker at the top of any table to hear the room.'
          : 'It plays quietly under the game, and stops whenever Banca is out of sight.'}
      </p>
    </>
  )
}
