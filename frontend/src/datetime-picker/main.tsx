import { createRoot } from 'react-dom/client'
import { DateTimePicker } from './DateTimePicker'
import { readPickerContext } from './context'
import './DateTimePicker.css'

// Read once before rendering and remove our hints from the current history entry.
// No authentication token, Entry data or API access is needed on this isolated page.
const context = readPickerContext(window.location.hash)
window.history.replaceState(null, '', window.location.pathname)
createRoot(document.getElementById('root')!).render(<DateTimePicker context={context} />)
