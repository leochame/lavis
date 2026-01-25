import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { PlatformProvider } from './platforms/PlatformProvider'

console.log('main.tsx: Starting app...');
console.log('main.tsx: Root element:', document.getElementById('root'));

const root = createRoot(document.getElementById('root')!);
root.render(
  <StrictMode>
    <PlatformProvider>
      <App />
    </PlatformProvider>
  </StrictMode>,
);

console.log('main.tsx: App rendered');
