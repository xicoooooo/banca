import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { registerServiceWorker } from './casino/install'
import { readTableStyle, wear } from './style/tableStyle'
import { Ambience } from './casino/Ambience'
import { light, readAtmosphere } from './casino/atmosphere'

registerServiceWorker()
// Before anything is drawn, so the room never flashes in the wrong colours.
wear(readTableStyle())
light(readAtmosphere().theme)

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
    <Ambience />
  </StrictMode>,
)
