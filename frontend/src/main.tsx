import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { registerServiceWorker } from './casino/install'
import { readTableStyle, wear } from './style/tableStyle'

registerServiceWorker()
// Before anything is drawn, so the room never flashes in the wrong colours.
wear(readTableStyle())

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
