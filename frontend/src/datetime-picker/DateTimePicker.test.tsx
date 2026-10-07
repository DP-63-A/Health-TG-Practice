import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { DateTimePicker } from './DateTimePicker'
import { readPickerContext, type PickerContext } from './context'

const context: PickerContext = { token: 'a'.repeat(64), revision: 7, zone: 'Europe/Vilnius', label: 'Дата ёжика 🙂', date: '', time: '', withTime: true, dateLocked: false, timeLocked: false }
const fragment = (patch: Partial<Record<keyof PickerContext, string>> = {}) => '#' + new URLSearchParams({ ...Object.fromEntries(Object.entries(context).map(([k,v]) => [k,String(v)])), ...patch }).toString()
afterEach(cleanup)
describe('Issue118 standalone picker', () => {
  it('has empty unknown values, profile timezone, and sends only selection metadata once', () => {
    const sendData=vi.fn(); const ready=vi.fn()
    render(<DateTimePicker context={context} telegram={{sendData,ready}} />)
    expect(ready).toHaveBeenCalledOnce()
    expect(screen.getByLabelText('Дата')).toHaveValue('')
    expect(screen.getByLabelText('Время')).toHaveValue('')
    expect(screen.getByText('Europe/Vilnius')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Дата'),{target:{value:'2026-10-06'}})
    fireEvent.change(screen.getByLabelText('Время'),{target:{value:'14:30'}})
    fireEvent.click(screen.getByRole('button',{name:'Применить'}))
    expect(JSON.parse(sendData.mock.calls[0][0])).toEqual({v:1,token:context.token,revision:7,date:'2026-10-06',time:'14:30'})
    fireEvent.click(screen.getByRole('button',{name:'Применить'}))
    expect(sendData).toHaveBeenCalledOnce()
    expect(screen.getByRole('status')).toHaveTextContent('Проверьте ответ бота')
  })
  it('cancel closes without sending or calling protected APIs', () => {
    const close=vi.fn(); const sendData=vi.fn(); const fetch=vi.spyOn(window,'fetch')
    render(<DateTimePicker context={context} telegram={{close,sendData}} />)
    fireEvent.click(screen.getByRole('button',{name:'Отмена'}))
    expect(close).toHaveBeenCalledOnce(); expect(sendData).not.toHaveBeenCalled(); expect(fetch).not.toHaveBeenCalled()
    fetch.mockRestore()
  })
  it('date-only metrics do not send time and existing fields stay readonly', () => {
    const sendData=vi.fn()
    render(<DateTimePicker context={{...context,date:'2026-10-05',dateLocked:true,withTime:false}} telegram={{sendData}} />)
    expect(screen.getByLabelText(/Дата/)).toHaveAttribute('readonly')
    expect(screen.queryByLabelText(/Время/)).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button',{name:'Применить'}))
    expect(JSON.parse(sendData.mock.calls[0][0])).not.toHaveProperty('time')
  })
  it('send failure allows retry without claiming a successful application', () => {
    const sendData=vi.fn().mockImplementationOnce(()=>{throw Error('offline')})
    render(<DateTimePicker context={{...context,date:'2026-10-05',time:'12:30'}} telegram={{sendData}} />)
    fireEvent.click(screen.getByRole('button',{name:'Применить'}))
    expect(screen.getByRole('alert')).toHaveTextContent('Не удалось передать')
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button',{name:'Применить'})); expect(sendData).toHaveBeenCalledTimes(2)
  })
  it('outside Telegram cannot claim delivery', () => {
    render(<DateTimePicker context={{...context,date:'2026-10-05',time:'12:30'}} telegram={{}} />)
    fireEvent.click(screen.getByRole('button',{name:'Применить'}))
    expect(screen.getByRole('alert')).toHaveTextContent('Откройте это окно кнопкой')
  })
  it('rejects absent or corrupted context without an enabled form',()=>{
    render(<DateTimePicker context={null} telegram={{}} />)
    expect(screen.queryByRole('button',{name:'Применить'})).not.toBeInTheDocument()
  })
  it('parses unicode and locked precision without inventing fields',()=>{
    expect(readPickerContext(fragment())).toEqual(context)
    expect(readPickerContext(fragment({date:'2026-10-05',time:'12:30:10.123456789',timeLocked:'true'}))?.time).toBe('12:30:10.123456789')
  })
  it.each([{token:'bad'},{revision:'0'},{revision:'9007199254740992'},{zone:'No/SuchZone'},{withTime:'yes'},{dateLocked:'true'},{timeLocked:'true'},{label:''},{date:'5.10.2026'}])('rejects malformed presentation %j',patch=>{
    expect(readPickerContext(fragment(patch))).toBeNull()
  })
})
