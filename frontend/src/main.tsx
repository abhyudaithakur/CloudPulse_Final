import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '@fontsource/overpass/400.css';
import '@fontsource/overpass/600.css';
import '@fontsource/overpass/800.css';
import '@fontsource/overpass-mono/400.css';
import App from './App';
import './styles/app.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
